package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.core.SessionOperationFailure
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ConnectionOwner(
    private val gateway: ProtocolGateway,
    private val metadata: SessionMetadata,
    private val children: SessionChildren,
    defaultDispatcher: CoroutineDispatcher,
    private val backoff: ReconnectBackoff,
    private val onShutdown: () -> Unit = {},
) : TvheadendSession {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(defaultDispatcher + rootJob)
    private val commandMutex = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Disconnected)

    private var selectedProfile: ServerProfile? = null
    private var worker: Job? = null
    private var activeToken: SessionToken? = null
    private var retryDisposition: RetryDisposition? = null
    private var closed = false
    private var shutdownCompletion: CompletableDeferred<Unit>? = null

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    override suspend fun connect(profile: ServerProfile): SessionCommandResult {
        currentCoroutineContext().ensureActive()
        return commandMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (closed) {
                return@withLock SessionCommandResult.SHUT_DOWN
            }
            if (selectedProfile?.hasSameConfigurationAs(profile) == true) {
                return@withLock SessionCommandResult.NO_CHANGE
            }

            withContext(NonCancellable) {
                if (selectedProfile != null || worker != null) {
                    selectedProfile = null
                    tearDownReusableSession()
                }
                selectedProfile = profile
                startWorker(profile)
            }
            currentCoroutineContext().ensureActive()
            SessionCommandResult.STARTED
        }
    }

    override suspend fun retry(): SessionCommandResult {
        currentCoroutineContext().ensureActive()
        return commandMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (closed) {
                return@withLock SessionCommandResult.SHUT_DOWN
            }
            val profile = selectedProfile ?: return@withLock SessionCommandResult.NO_ACTIVE_PROFILE
            when (currentRetryDisposition()) {
                null -> SessionCommandResult.NO_CHANGE
                RetryDisposition.CONFIGURATION_CHANGE -> SessionCommandResult.RETRY_NOT_ALLOWED
                RetryDisposition.BACKOFF,
                RetryDisposition.EXPLICIT,
                -> {
                    withContext(NonCancellable) {
                        selectedProfile = null
                        tearDownReusableSession()
                        selectedProfile = profile
                        startWorker(profile)
                    }
                    currentCoroutineContext().ensureActive()
                    SessionCommandResult.STARTED
                }
            }
        }
    }

    override suspend fun disconnect() {
        currentCoroutineContext().ensureActive()
        commandMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (closed || (selectedProfile == null && worker == null)) {
                return@withLock
            }
            withContext(NonCancellable) {
                selectedProfile = null
                tearDownReusableSession()
            }
            currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun shutdown() {
        val plan = commandMutex.withLock {
            shutdownCompletion?.let { return@withLock ShutdownPlan.Wait(it) }

            val completion = CompletableDeferred<Unit>()
            shutdownCompletion = completion
            closed = true
            val activeWorker = invalidateSession()
            selectedProfile = null
            val admissionFailure = captureFailure { children.stopAdmission() }
            ShutdownPlan.Run(completion, activeWorker, admissionFailure)
        }

        when (plan) {
            is ShutdownPlan.Wait -> plan.completion.await()
            is ShutdownPlan.Run -> {
                withContext(NonCancellable) {
                    try {
                        try {
                            runOrderedCleanup(
                                initialFailure = plan.admissionFailure,
                                steps = listOf(
                                    {
                                        plan.worker?.cancelAndJoin()
                                        Unit
                                    },
                                    children::cancelAndJoinEpgWorker,
                                    children::closeAndJoinSubscriptions,
                                    gateway::disconnect,
                                    gateway::shutdown,
                                    { metadata.resetWorkingStateRetainingPublishedSnapshot() },
                                ),
                            )
                            mutableState.value = SessionState.Disconnected
                        } finally {
                            try {
                                rootJob.cancelAndJoin()
                            } finally {
                                onShutdown()
                            }
                        }
                        plan.completion.complete(Unit)
                    } catch (exception: Throwable) {
                        plan.completion.completeExceptionally(exception)
                        throw exception
                    }
                }
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun tearDownReusableSession() {
        val activeWorker = invalidateSession()
        val admissionFailure = captureFailure { children.stopAdmission() }
        runOrderedCleanup(
            initialFailure = admissionFailure,
            steps = listOf(
                {
                    activeWorker?.cancelAndJoin()
                    Unit
                },
                children::cancelAndJoinEpgWorker,
                children::closeAndJoinSubscriptions,
                gateway::disconnect,
                { metadata.resetWorkingStateRetainingPublishedSnapshot() },
            ),
        )
    }

    private fun invalidateSession(): Job? {
        val activeWorker = worker
        worker = null
        synchronized(stateLock) {
            activeToken = null
            retryDisposition = null
            mutableState.value = SessionState.Disconnected
        }
        return activeWorker
    }

    private fun startWorker(profile: ServerProfile) {
        val token = SessionToken()
        synchronized(stateLock) {
            activeToken = token
            retryDisposition = null
        }
        worker = scope.launch(start = CoroutineStart.LAZY) {
            connectionLoop(profile, token)
        }.also(Job::start)
    }

    private suspend fun connectionLoop(profile: ServerProfile, token: SessionToken) {
        try {
            connectionLoopBody(profile, token)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            commitUnavailable(
                token,
                SessionFailure.UnexpectedFailure,
                RetryDisposition.EXPLICIT,
            )
        }
    }

    private suspend fun connectionLoopBody(profile: ServerProfile, token: SessionToken) {
        var failureIndex = 0
        while (currentCoroutineContext().isActive && isCurrent(token)) {
            clearRetryDisposition(token)
            val outcome = try {
                runConnectionAttempt(profile, token)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                AttemptOutcome(
                    failure = SessionFailure.UnexpectedFailure,
                    disposition = RetryDisposition.EXPLICIT,
                    connected = true,
                    reachedReady = false,
                )
            }

            if (outcome.reachedReady) {
                failureIndex = 0
            }
            if (!commitUnavailable(token, outcome.failure, outcome.disposition)) {
                return
            }
            if (outcome.connected) {
                val admissionFailure = captureFailure { children.stopAdmission() }
                runOrderedCleanup(
                    initialFailure = admissionFailure,
                    steps = listOf(
                        children::cancelAndJoinEpgWorker,
                        children::closeAndJoinSubscriptions,
                        gateway::disconnect,
                        { metadata.resetWorkingStateRetainingPublishedSnapshot() },
                    ),
                )
            }
            if (outcome.disposition != RetryDisposition.BACKOFF) {
                return
            }

            val retryDelay = backoff.delayForRetry(failureIndex)
            failureIndex += 1
            delay(retryDelay)
        }
    }

    private suspend fun runConnectionAttempt(
        profile: ServerProfile,
        token: SessionToken,
    ): AttemptOutcome = coroutineScope {
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        requireCurrent(token)
        commitState(token, SessionState.Connecting)

        val generation = CompletableDeferred<GatewayGeneration>()
        val runtimeFailure = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.connectionFailures.first { event ->
                event.generation === generation.await()
            }.failure
        }
        var metadataWorker: Job? = null
        try {
            val connection = when (val result = gateway.connect(profile.toGatewayConfiguration())) {
                is GatewayConnectResult.Connected -> result.connection
                is GatewayConnectResult.Failed -> return@coroutineScope result.failure.toAttemptOutcome()
            }
            generation.complete(connection.generation)
            requireCurrent(token)
            metadata.bindGeneration(connection.generation)
            metadata.applyDvrAccess(connection.generation, connection.dvrAccess)
            metadata.publishServerFacts(connection.generation, connection.serverFacts)
            metadataWorker = launch(start = CoroutineStart.UNDISPATCHED) {
                gateway.metadata.collect(metadata::acceptMetadata)
            }
            commitState(token, SessionState.Synchronizing)

            val synchronization = async {
                synchronize(connection.generation)
            }
            when (
                val synchronizationOutcome = select<SynchronizationOutcome> {
                    synchronization.onAwait { it }
                    runtimeFailure.onAwait { SynchronizationOutcome.ConnectionFailed(it) }
                }
            ) {
                is SynchronizationOutcome.Failed -> return@coroutineScope synchronizationOutcome.outcome
                is SynchronizationOutcome.ConnectionFailed -> {
                    synchronization.cancelAndJoin()
                    return@coroutineScope synchronizationOutcome.failure.toAttemptOutcome(
                        connected = true,
                    )
                }
                SynchronizationOutcome.Ready -> Unit
            }

            requireCurrent(token)
            val capabilities = metadata.capabilities(connection.generation)
            val committed = gateway.commitIfLive(connection.generation) {
                commitReady(token, capabilities)
            } == true
            if (!committed) {
                requireCurrent(token)
                return@coroutineScope GatewayConnectionFailure.TRANSPORT_UNAVAILABLE.toAttemptOutcome(
                    connected = true,
                )
            }
            runtimeFailure.await().toAttemptOutcome(
                connected = true,
                reachedReady = true,
            )
        } finally {
            metadataWorker?.cancelAndJoin()
            runtimeFailure.cancelAndJoin()
        }
    }

    private suspend fun synchronize(generation: GatewayGeneration): SynchronizationOutcome {
        if (gateway.commitIfLive(generation) { true } != true) {
            return SynchronizationOutcome.ConnectionFailed(
                GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
            )
        }
        return when (val result = gateway.enableInitialMetadata(generation)) {
            is GatewayResult.Ok -> {
                metadata.awaitChannelsAndTagsCurrent(generation)
                SynchronizationOutcome.Ready
            }
            GatewayResult.ServerRejected -> GatewayResult.ServerRejected.toSynchronizationFailure()
            GatewayResult.AccessDenied -> GatewayResult.AccessDenied.toSynchronizationFailure()
            GatewayResult.ConnectionLimit -> GatewayResult.ConnectionLimit.toSynchronizationFailure()
            GatewayResult.Timeout -> GatewayResult.Timeout.toSynchronizationFailure()
            GatewayResult.TransportUnavailable ->
                GatewayResult.TransportUnavailable.toSynchronizationFailure()
            GatewayResult.NotSupported -> GatewayResult.NotSupported.toSynchronizationFailure()
        }
    }

    private fun commitState(token: SessionToken, state: SessionState) {
        synchronized(stateLock) {
            if (activeToken !== token || closed) {
                throw CancellationException("Session generation is no longer active")
            }
            mutableState.value = state
        }
    }

    private fun commitReady(
        token: SessionToken,
        capabilities: at.bernhardberger.tvheadend.sdk.core.ServerCapabilities,
    ): Boolean = synchronized(stateLock) {
        if (activeToken !== token || closed) {
            false
        } else {
            retryDisposition = null
            mutableState.value = SessionState.Ready(capabilities)
            true
        }
    }

    private fun requireCurrent(token: SessionToken) {
        if (!isCurrent(token)) {
            throw CancellationException("Session generation is no longer active")
        }
    }

    private fun isCurrent(token: SessionToken): Boolean = synchronized(stateLock) {
        activeToken === token && !closed
    }

    private fun clearRetryDisposition(token: SessionToken) {
        synchronized(stateLock) {
            if (activeToken === token && !closed) {
                retryDisposition = null
            }
        }
    }

    private fun commitUnavailable(
        token: SessionToken,
        failure: SessionFailure,
        disposition: RetryDisposition,
    ): Boolean = synchronized(stateLock) {
        if (activeToken !== token || closed) {
            false
        } else {
            retryDisposition = disposition
            mutableState.value = SessionState.Unavailable(failure)
            true
        }
    }

    private fun currentRetryDisposition(): RetryDisposition? = synchronized(stateLock) {
        retryDisposition
    }

    private class SessionToken
}

private fun captureFailure(block: () -> Unit): Throwable? = try {
    block()
    null
} catch (failure: Throwable) {
    failure
}

private suspend fun runOrderedCleanup(
    initialFailure: Throwable?,
    steps: List<suspend () -> Unit>,
) {
    var firstFailure = initialFailure
    steps.forEach { step ->
        try {
            step()
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            }
        }
    }
    firstFailure?.let { throw it }
}

private enum class RetryDisposition {
    BACKOFF,
    EXPLICIT,
    CONFIGURATION_CHANGE,
}

private class AttemptOutcome(
    internal val failure: SessionFailure,
    internal val disposition: RetryDisposition,
    internal val connected: Boolean,
    internal val reachedReady: Boolean,
)

private sealed interface SynchronizationOutcome {
    public data object Ready : SynchronizationOutcome

    public class Failed(
        internal val outcome: AttemptOutcome,
    ) : SynchronizationOutcome

    public class ConnectionFailed(
        internal val failure: GatewayConnectionFailure,
    ) : SynchronizationOutcome
}

private sealed interface ShutdownPlan {
    public class Run(
        internal val completion: CompletableDeferred<Unit>,
        internal val worker: Job?,
        internal val admissionFailure: Throwable?,
    ) : ShutdownPlan

    public class Wait(
        internal val completion: Deferred<Unit>,
    ) : ShutdownPlan
}

private fun GatewayConnectionFailure.toAttemptOutcome(
    connected: Boolean = false,
    reachedReady: Boolean = false,
): AttemptOutcome = when (this) {
    GatewayConnectionFailure.AUTHENTICATION_REJECTED -> AttemptOutcome(
        SessionFailure.AuthenticationRejected,
        RetryDisposition.CONFIGURATION_CHANGE,
        connected,
        reachedReady,
    )
    GatewayConnectionFailure.PERMISSION_DENIED -> AttemptOutcome(
        SessionFailure.PermissionDenied,
        RetryDisposition.CONFIGURATION_CHANGE,
        connected,
        reachedReady,
    )
    GatewayConnectionFailure.SERVER_UNREACHABLE -> AttemptOutcome(
        SessionFailure.ServerUnreachable,
        RetryDisposition.BACKOFF,
        connected,
        reachedReady,
    )
    GatewayConnectionFailure.NETWORK_UNAVAILABLE -> AttemptOutcome(
        SessionFailure.NetworkUnavailable,
        RetryDisposition.BACKOFF,
        connected,
        reachedReady,
    )
    GatewayConnectionFailure.INCOMPATIBLE_SERVER -> AttemptOutcome(
        SessionFailure.IncompatibleServer,
        RetryDisposition.CONFIGURATION_CHANGE,
        connected,
        reachedReady,
    )
    GatewayConnectionFailure.NO_CHANNELS -> AttemptOutcome(
        SessionFailure.NoChannels,
        RetryDisposition.EXPLICIT,
        connected,
        reachedReady,
    )
    GatewayConnectionFailure.TRANSPORT_UNAVAILABLE -> AttemptOutcome(
        SessionFailure.TransportUnavailable,
        RetryDisposition.BACKOFF,
        connected,
        reachedReady,
    )
}

private fun GatewayResult<*>.toSynchronizationFailure(): SynchronizationOutcome.Failed = when (this) {
    is GatewayResult.Ok -> error("A successful result is not a synchronization failure")
    GatewayResult.ServerRejected -> synchronizationFailure(
        SessionOperationFailure.SERVER_REJECTED,
        RetryDisposition.EXPLICIT,
    )
    GatewayResult.AccessDenied -> synchronizationFailure(
        SessionOperationFailure.ACCESS_DENIED,
        RetryDisposition.CONFIGURATION_CHANGE,
    )
    GatewayResult.ConnectionLimit -> synchronizationFailure(
        SessionOperationFailure.CONNECTION_LIMIT,
        RetryDisposition.BACKOFF,
    )
    GatewayResult.Timeout -> synchronizationFailure(
        SessionOperationFailure.TIMEOUT,
        RetryDisposition.BACKOFF,
    )
    GatewayResult.TransportUnavailable -> synchronizationFailure(
        SessionOperationFailure.TRANSPORT_UNAVAILABLE,
        RetryDisposition.BACKOFF,
    )
    GatewayResult.NotSupported -> synchronizationFailure(
        SessionOperationFailure.NOT_SUPPORTED,
        RetryDisposition.CONFIGURATION_CHANGE,
    )
}

private fun synchronizationFailure(
    failure: SessionOperationFailure,
    disposition: RetryDisposition,
): SynchronizationOutcome.Failed = SynchronizationOutcome.Failed(
    AttemptOutcome(
        failure = SessionFailure.SynchronizationFailed(failure),
        disposition = disposition,
        connected = true,
        reachedReady = false,
    ),
)
