@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.core.SessionOperationFailure
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionRecoveryDisposition
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.SessionPlaybackBindingFactory
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
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
import kotlinx.coroutines.flow.StateFlow
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
    private val dvrMutations: DvrMutationLifecycle = DvrMutationLifecycle.None,
    private val dvrProgress: DvrProgressLifecycle = DvrProgressLifecycle.None,
    defaultDispatcher: CoroutineDispatcher,
    private val backoff: ReconnectBackoff,
    private val beforeDvrCapabilityPublication: suspend (Boolean) -> Unit = {},
    private val onShutdown: () -> Unit = {},
) : TvheadendSession {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(defaultDispatcher + rootJob)
    private val commandMutex = Mutex()
    private val stateLock = Any()
    private var selectedProfile: ServerProfile? = null
    private var retainedProfile: ServerProfile? = null
    private var worker: Job? = null
    private var activeToken: SessionToken? = null
    private var activeGeneration: GatewayGeneration? = null
    private var latestDvrCapabilityRevision: Long? = null
    private var retryDisposition: SessionRecoveryDisposition? = null
    private var closed = false
    private var shutdownCompletion: CompletableDeferred<Unit>? = null
    private val playbackBindings = SessionPlaybackBindingFactory(metadata, children)

    override val observation: StateFlow<SessionObservation> = metadata.observation
    override val epgRepository: EpgRepository = metadata.epgRepository
    override val dvrRepository: DvrRepository = metadata.dvrRepository
    override val artwork: ArtworkLoader = children
    override suspend fun getStreamProfiles(
        currentSession: CurrentSessionObservation,
    ): StreamProfilesResult {
        currentCoroutineContext().ensureActive()
        val generation = metadata.resolveGeneration(currentSession)
            ?: return StreamProfilesResult.ObservationExpired
        return children.getStreamProfiles(generation, currentSession)
    }
    override fun bindLivePlayback(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): PlaybackBindingResult<PlaybackBinding.Live> =
        playbackBindings.bindLive(currentSession, channelId)

    override fun bindRecordingPlayback(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording> =
        playbackBindings.bindRecording(currentSession, recordingId)

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
                    retainedProfile = null
                    tearDownReusableSession(retainPublishedCatalog = false)
                } else if (retainedProfile?.hasSameConfigurationAs(profile) != true) {
                    retainedProfile = null
                    synchronized(stateLock) {
                        metadata.clearAllState()
                    }
                }
                selectedProfile = profile
                retainedProfile = null
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
                SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
                SessionRecoveryDisposition.NO_RETRY,
                -> SessionCommandResult.RETRY_NOT_ALLOWED
                SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
                SessionRecoveryDisposition.EXPLICIT_RETRY,
                -> {
                    withContext(NonCancellable) {
                        selectedProfile = null
                        tearDownReusableSession(retainPublishedCatalog = true)
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
                retainedProfile = selectedProfile
                selectedProfile = null
                tearDownReusableSession(retainPublishedCatalog = true)
            }
            currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun shutdown() {
        val plan = commandMutex.withLock {
            shutdownCompletion?.let { return@withLock ShutdownPlan.Wait(it) }

            val completion = CompletableDeferred<Unit>()
            shutdownCompletion = completion
            val invalidated = invalidateSession(
                retainPublishedCatalog = false,
                terminal = true,
            )
            selectedProfile = null
            retainedProfile = null
            ShutdownPlan.Run(completion, invalidated.worker, invalidated.admissionFailure)
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
                                    children::cancelAndJoinBackgroundEnrichment,
                                    children::closeAndJoinSubscriptions,
                                    gateway::disconnect,
                                    gateway::shutdown,
                                    { metadata.clearAllState() },
                                ),
                            )
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

    private suspend fun tearDownReusableSession(retainPublishedCatalog: Boolean) {
        val invalidated = invalidateSession(retainPublishedCatalog)
        runOrderedCleanup(
            initialFailure = invalidated.admissionFailure,
            steps = listOf(
                {
                    invalidated.worker?.cancelAndJoin()
                    Unit
                },
                children::cancelAndJoinBackgroundEnrichment,
                children::closeAndJoinSubscriptions,
                gateway::disconnect,
                {
                    if (retainPublishedCatalog) {
                        metadata.resetWorkingStateRetainingPublishedSnapshot()
                    } else {
                        metadata.clearAllState()
                    }
                },
            ),
        )
    }

    private fun invalidateSession(
        retainPublishedCatalog: Boolean,
        terminal: Boolean = false,
    ): InvalidatedSession {
        val activeWorker = worker
        worker = null
        val admissionFailure = synchronized(stateLock) {
            if (terminal) closed = true
            activeToken = null
            activeGeneration = null
            latestDvrCapabilityRevision = null
            retryDisposition = null
            var failure = captureFailure { dvrMutations.stopAdmission() }
            failure = captureFailure(failure) { dvrProgress.stopAdmission() }
            failure = captureFailure(failure) { children.stopAdmission() }
            if (retainPublishedCatalog) {
                metadata.resetWorkingStateRetainingPublishedSnapshot()
            } else {
                metadata.clearAllState()
            }
            metadata.publishSessionState(
                state = SessionState.Disconnected,
                progressCapability = RecordingProgressCapability.UNKNOWN,
                generation = null,
            )
            failure
        }
        return InvalidatedSession(activeWorker, admissionFailure)
    }

    private fun startWorker(profile: ServerProfile) {
        val token = SessionToken()
        synchronized(stateLock) {
            activeToken = token
            latestDvrCapabilityRevision = null
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
                SessionFailure.UnexpectedFailure.recoveryDisposition,
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
                    disposition = SessionFailure.UnexpectedFailure.recoveryDisposition,
                    connected = true,
                    reachedReady = false,
                )
            }

            if (outcome.reachedReady) {
                failureIndex = 0
            }
            val unavailable = commitUnavailable(token, outcome.failure, outcome.disposition)
            if (!unavailable.committed) {
                return
            }
            if (outcome.connected) {
                withContext(NonCancellable) {
                    runOrderedCleanup(
                        initialFailure = unavailable.admissionFailure,
                        steps = listOf(
                            children::cancelAndJoinBackgroundEnrichment,
                            children::closeAndJoinSubscriptions,
                            gateway::disconnect,
                            { metadata.resetWorkingStateRetainingPublishedSnapshot() },
                        ),
                    )
                }
                currentCoroutineContext().ensureActive()
            } else if (unavailable.admissionFailure != null) {
                throw unavailable.admissionFailure
            }
            if (outcome.disposition != SessionRecoveryDisposition.AUTOMATIC_BACKOFF) {
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
            dvrMutations.bindGeneration(connection.generation)
            dvrProgress.bindGeneration(connection.generation, connection.protocolVersion)
            children.bindGeneration(connection.generation)
            metadata.bindGeneration(connection.generation)
            metadata.applyDvrAccess(connection.generation, connection.dvrAccess)
            metadata.publishServerFacts(connection.generation, connection.serverFacts)
            metadataWorker = launch(start = CoroutineStart.UNDISPATCHED) {
                gateway.metadata.collect(metadata::acceptMetadata)
            }
            commitState(token, SessionState.Synchronizing)
            val liveAdmissionCommitted = gateway.commitIfLive(connection.generation) {
                commitLiveAdmission(
                    token = token,
                    generation = connection.generation,
                    streamingAccess = connection.serverFacts.streaming.toCapabilityAccess(),
                )
            } == true
            if (!liveAdmissionCommitted) {
                requireCurrent(token)
                return@coroutineScope GatewayConnectionFailure.TRANSPORT_UNAVAILABLE.toAttemptOutcome(
                    connected = true,
                )
            }

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
            if (
                !children.prepareBackgroundEnrichment(
                    connection.generation,
                    ::publishDvrCapabilitySnapshot,
                )
            ) {
                return@coroutineScope GatewayConnectionFailure.TRANSPORT_UNAVAILABLE.toAttemptOutcome(
                    connected = true,
                )
            }
            val capabilities = metadata.capabilitySnapshot(connection.generation)
            val protocolVersion = connection.protocolVersion
            val progressCapability = when {
                protocolVersion == null -> RecordingProgressCapability.UNKNOWN
                protocolVersion < DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION ->
                    RecordingProgressCapability.UNSUPPORTED
                else -> RecordingProgressCapability.SUPPORTED
            }
            val committed = gateway.commitIfLive(connection.generation) {
                commitReady(token, connection.generation, capabilities, progressCapability)
            } == true
            if (!committed) {
                requireCurrent(token)
                return@coroutineScope GatewayConnectionFailure.TRANSPORT_UNAVAILABLE.toAttemptOutcome(
                    connected = true,
                )
            }
            if (!children.startBackgroundEnrichment(connection.generation)) {
                return@coroutineScope GatewayConnectionFailure.TRANSPORT_UNAVAILABLE.toAttemptOutcome(
                    connected = true,
                    reachedReady = true,
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
            is GatewayResult.Ok -> try {
                metadata.awaitMetadataCurrent(generation)
                SynchronizationOutcome.Ready
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                SynchronizationOutcome.ConnectionFailed(
                    GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
                )
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
            metadata.publishSessionState(
                state = state,
                progressCapability = RecordingProgressCapability.UNKNOWN,
                generation = null,
            )
        }
    }

    private fun commitLiveAdmission(
        token: SessionToken,
        generation: GatewayGeneration,
        streamingAccess: CapabilityAccess,
    ): Boolean = synchronized(stateLock) {
        activeToken === token &&
            !closed &&
            children.startLiveAdmission(generation, streamingAccess)
    }

    private fun commitReady(
        token: SessionToken,
        generation: GatewayGeneration,
        capabilities: SessionCapabilitiesSnapshot,
        progressCapability: RecordingProgressCapability,
    ): Boolean = synchronized(stateLock) {
        if (activeToken !== token || closed || capabilities.generation !== generation) {
            false
        } else if (!dvrMutations.startAdmission(generation)) {
            false
        } else if (!dvrProgress.startAdmission(generation)) {
            dvrMutations.stopAdmission()
            false
        } else {
            activeGeneration = generation
            latestDvrCapabilityRevision = capabilities.revision
            retryDisposition = null
            metadata.publishSessionState(
                state = SessionState.Ready(capabilities.capabilities),
                progressCapability = progressCapability,
                generation = generation,
            )
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
        disposition: SessionRecoveryDisposition,
    ): UnavailableCommit = synchronized(stateLock) {
        if (activeToken !== token || closed) {
            UnavailableCommit(committed = false, admissionFailure = null)
        } else {
            activeGeneration = null
            latestDvrCapabilityRevision = null
            retryDisposition = disposition
            var admissionFailure = captureFailure { dvrMutations.stopAdmission() }
            admissionFailure = captureFailure(admissionFailure) { dvrProgress.stopAdmission() }
            admissionFailure = captureFailure(admissionFailure) { children.stopAdmission() }
            metadata.resetWorkingStateRetainingPublishedSnapshot()
            metadata.publishSessionState(
                state = SessionState.Unavailable(failure),
                progressCapability = RecordingProgressCapability.UNKNOWN,
                generation = null,
            )
            UnavailableCommit(committed = true, admissionFailure = admissionFailure)
        }
    }

    private fun currentRetryDisposition(): SessionRecoveryDisposition? = synchronized(stateLock) {
        retryDisposition
    }

    internal suspend fun applyDvrAccessProof(generation: GatewayGeneration, allowed: Boolean) {
        val snapshot = synchronized(stateLock) {
            if (
                closed ||
                activeGeneration !== generation ||
                observation.value.sessionState !is SessionState.Ready
            ) {
                null
            } else {
                metadata.applyDvrMutationProof(generation, allowed)
            }
        } ?: return

        publishDvrCapabilitySnapshot(snapshot)
    }

    private suspend fun publishDvrCapabilitySnapshot(snapshot: SessionCapabilitiesSnapshot) {
        val accepted = synchronized(stateLock) {
            if (
                closed ||
                activeGeneration !== snapshot.generation ||
                observation.value.sessionState !is SessionState.Ready ||
                snapshot.revision <= (latestDvrCapabilityRevision ?: Long.MIN_VALUE)
            ) {
                false
            } else {
                latestDvrCapabilityRevision = snapshot.revision
                true
            }
        }
        if (!accepted) return

        beforeDvrCapabilityPublication(snapshot.capabilities.dvrWrite == CapabilityAccess.ALLOWED)
        synchronized(stateLock) {
            if (
                !closed &&
                activeGeneration === snapshot.generation &&
                latestDvrCapabilityRevision == snapshot.revision &&
                observation.value.sessionState is SessionState.Ready
            ) {
                metadata.publishSessionState(
                    state = SessionState.Ready(snapshot.capabilities),
                    progressCapability = observation.value.recordingProgressCapability,
                    generation = snapshot.generation,
                )
            }
        }
    }

    internal fun applyRecordingProgressNotSupported(generation: GatewayGeneration) {
        synchronized(stateLock) {
            if (
                !closed &&
                activeGeneration === generation &&
                observation.value.sessionState is SessionState.Ready
            ) {
                metadata.publishSessionState(
                    state = observation.value.sessionState,
                    progressCapability = RecordingProgressCapability.UNSUPPORTED,
                    generation = generation,
                )
            }
        }
    }

    internal fun isDvrMutationReady(generation: GatewayGeneration): Boolean = synchronized(stateLock) {
        !closed &&
            activeGeneration === generation &&
            observation.value.sessionState is SessionState.Ready
    }

    private class SessionToken
}

private fun captureFailure(block: () -> Unit): Throwable? = try {
    block()
    null
} catch (failure: Throwable) {
    failure
}

private fun captureFailure(previous: Throwable?, block: () -> Unit): Throwable? = try {
    block()
    previous
} catch (failure: Throwable) {
    previous ?: failure
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

private class AttemptOutcome(
    internal val failure: SessionFailure,
    internal val disposition: SessionRecoveryDisposition,
    internal val connected: Boolean,
    internal val reachedReady: Boolean,
)

private class InvalidatedSession(
    internal val worker: Job?,
    internal val admissionFailure: Throwable?,
)

private class UnavailableCommit(
    internal val committed: Boolean,
    internal val admissionFailure: Throwable?,
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
): AttemptOutcome {
    val failure = when (this) {
        GatewayConnectionFailure.AUTHENTICATION_REJECTED -> SessionFailure.AuthenticationRejected
        GatewayConnectionFailure.PERMISSION_DENIED -> SessionFailure.PermissionDenied
        GatewayConnectionFailure.SERVER_UNREACHABLE -> SessionFailure.ServerUnreachable
        GatewayConnectionFailure.NETWORK_UNAVAILABLE -> SessionFailure.NetworkUnavailable
        GatewayConnectionFailure.INCOMPATIBLE_SERVER -> SessionFailure.IncompatibleServer
        GatewayConnectionFailure.NO_CHANNELS -> SessionFailure.NoChannels
        GatewayConnectionFailure.TRANSPORT_UNAVAILABLE -> SessionFailure.TransportUnavailable
    }
    return AttemptOutcome(
        failure = failure,
        disposition = failure.recoveryDisposition,
        connected = connected,
        reachedReady = reachedReady,
    )
}

private fun GatewayResult<*>.toSynchronizationFailure(): SynchronizationOutcome.Failed = when (this) {
    is GatewayResult.Ok -> error("A successful result is not a synchronization failure")
    GatewayResult.ServerRejected -> synchronizationFailure(SessionOperationFailure.SERVER_REJECTED)
    GatewayResult.AccessDenied -> synchronizationFailure(SessionOperationFailure.ACCESS_DENIED)
    GatewayResult.ConnectionLimit -> synchronizationFailure(SessionOperationFailure.CONNECTION_LIMIT)
    GatewayResult.Timeout -> synchronizationFailure(SessionOperationFailure.TIMEOUT)
    GatewayResult.TransportUnavailable ->
        synchronizationFailure(SessionOperationFailure.TRANSPORT_UNAVAILABLE)
    GatewayResult.NotSupported -> synchronizationFailure(SessionOperationFailure.NOT_SUPPORTED)
}

private fun synchronizationFailure(
    failure: SessionOperationFailure,
): SynchronizationOutcome.Failed = SynchronizationOutcome.Failed(
    AttemptOutcome(
        failure = SessionFailure.SynchronizationFailed(failure),
        disposition = failure.recoveryDisposition,
        connected = true,
        reachedReady = false,
    ),
)

private fun Boolean?.toCapabilityAccess(): CapabilityAccess = when (this) {
    true -> CapabilityAccess.ALLOWED
    false -> CapabilityAccess.DENIED
    null -> CapabilityAccess.UNKNOWN
}
