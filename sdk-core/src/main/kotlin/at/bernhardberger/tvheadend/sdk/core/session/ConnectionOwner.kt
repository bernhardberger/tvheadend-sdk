@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CachedArtworkReference
import at.bernhardberger.tvheadend.sdk.core.artworkCacheKey
import at.bernhardberger.tvheadend.sdk.core.cache.CacheNamespace
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
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCache
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import at.bernhardberger.tvheadend.sdk.core.recoveryDisposition
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionRecoveryDisposition
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.SessionPlaybackBindingFactory
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.cache.DisabledSessionCache
import at.bernhardberger.tvheadend.sdk.core.cache.MetadataCacheRuntime
import at.bernhardberger.tvheadend.sdk.core.cache.RestoredSnapshots
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
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
import kotlinx.coroutines.flow.Flow
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
    private val cacheRuntime: MetadataCacheRuntime? = null,
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
    private var attemptGeneration: GatewayGeneration? = null
    private var connectionAttemptRevision = 0L
    private var latestDvrCapabilityRevision: Long? = null
    private var retryDisposition: SessionRecoveryDisposition? = null
    private var closed = false
    private var shutdownCompletion: CompletableDeferred<Unit>? = null
    private val playbackBindings = SessionPlaybackBindingFactory(metadata, children)

    override val observation: StateFlow<SessionObservation> = metadata.observation
    override val epgRepository: EpgRepository = metadata.epgRepository
    override val dvrRepository: DvrRepository = metadata.dvrRepository
    private var artworkNamespace: CacheNamespace? = null
    override val artwork: ArtworkLoader = object : ArtworkLoader {
        override fun cacheKey(currentSession: CurrentSessionObservation, artworkId: ArtworkId): String? {
            val namespace = synchronized(stateLock) {
                artworkNamespace?.takeIf { isCurrent(currentSession) }
            } ?: return null
            return artworkCacheKey(namespace, artworkId)
        }

        @ExperimentalTvheadendApi
        override fun cachedArtwork(artworkId: ArtworkId): CachedArtworkReference? {
            val namespace = synchronized(stateLock) { artworkNamespace } ?: return null
            return CachedArtworkReference.create(namespace, artworkId)
        }

        @ExperimentalTvheadendApi
        override suspend fun loadCachedArtwork(reference: CachedArtworkReference): ArtworkLoadResult {
            currentCoroutineContext().ensureActive()
            // Namespaces are profile identities: a reference never reads under another profile.
            val isCurrent = { synchronized(stateLock) { artworkNamespace == reference.namespace } }
            val runtime = cacheRuntime
            if (runtime == null || !isCurrent()) {
                return ArtworkLoadResult.Unavailable(TvheadendFailure.OBSERVATION_EXPIRED)
            }
            return runtime.loadCachedArtwork(reference.namespace, reference.artworkId, isCurrent)
        }

        override suspend fun loadArtwork(
            currentSession: CurrentSessionObservation,
            artworkId: ArtworkId,
        ): ArtworkLoadResult {
            currentCoroutineContext().ensureActive()
            val namespace = synchronized(stateLock) {
                artworkNamespace.takeIf { isCurrent(currentSession) }
            }
            val runtime = cacheRuntime
            return if (namespace == null || runtime == null) {
                children.loadArtwork(currentSession, artworkId)
            } else {
                runtime.loadArtwork(namespace, artworkId, { isCurrent(currentSession) }) {
                    children.loadArtwork(currentSession, artworkId)
                }
            }
        }
    }
    override val cache: SessionCache = cacheRuntime ?: DisabledSessionCache
    override suspend fun getStreamProfiles(
        currentSession: CurrentSessionObservation,
    ): StreamProfilesResult {
        currentCoroutineContext().ensureActive()
        val generation = metadata.resolveGeneration(currentSession)
            ?: return StreamProfilesResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
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
                    tearDownReusableSession(retainPublishedCatalog = false, connectionRetired = true)
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
                SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
                SessionRecoveryDisposition.EXPLICIT_RETRY,
                -> {
                    withContext(NonCancellable) {
                        selectedProfile = null
                        tearDownReusableSession(retainPublishedCatalog = true, connectionRetired = true)
                        selectedProfile = profile
                        startWorker(profile)
                    }
                    currentCoroutineContext().ensureActive()
                    SessionCommandResult.STARTED
                }
                else -> SessionCommandResult.RETRY_NOT_ALLOWED
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
            ShutdownPlan.Run(
                completion,
                invalidated.worker,
                invalidated.admissionFailure,
                invalidated.retiredPublication,
            )
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
                                    {
                                        cacheRuntime?.shutdown(plan.retiredPublication)
                                        Unit
                                    },
                                    {
                                        metadata.clearAllState()
                                        Unit
                                    },
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

    private suspend fun tearDownReusableSession(
        retainPublishedCatalog: Boolean,
        connectionRetired: Boolean = false,
    ) {
        val invalidated = invalidateSession(retainPublishedCatalog, connectionRetired = connectionRetired)
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
                    cacheRuntime?.stop(invalidated.retiredPublication)
                    Unit
                },
                {
                    if (retainPublishedCatalog) {
                        metadata.resetWorkingStateRetainingPublishedSnapshot()
                    } else {
                        metadata.clearAllState()
                    }
                    Unit
                },
            ),
        )
    }

    private fun invalidateSession(
        retainPublishedCatalog: Boolean,
        terminal: Boolean = false,
        connectionRetired: Boolean = false,
    ): InvalidatedSession {
        val activeWorker = worker
        worker = null
        var retiredPublication: PublishedSnapshots? = null
        val admissionFailure = synchronized(stateLock) {
            if (terminal) closed = true
            activeToken = null
            connectionAttemptRevision += 1
            attemptGeneration = null
            artworkNamespace = null
            activeGeneration = null
            latestDvrCapabilityRevision = null
            retryDisposition = null
            var failure = captureFailure { dvrMutations.stopAdmission(connectionRetired) }
            failure = captureFailure(failure) { dvrProgress.stopAdmission(connectionRetired) }
            failure = captureFailure(failure) { children.stopAdmission() }
            // The cache writer flushes the outgoing publication to the outgoing namespace after
            // this clear, so capture it atomically with the reset.
            retiredPublication = if (retainPublishedCatalog) {
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
        return InvalidatedSession(activeWorker, admissionFailure, retiredPublication)
    }

    private fun startWorker(profile: ServerProfile) {
        val token = SessionToken()
        synchronized(stateLock) {
            activeToken = token
            // connect clears DVR state before changing accounts: observation equality ignores
            // username, so an account change alone cannot replace an equal StateFlow value.
            // Same-profile reconnect/retry retains both the account and its published snapshot.
            metadata.selectAccount((profile.authentication as? ServerAuthentication.Password)?.username)
            artworkNamespace = if (cacheRuntime != null) profile.cacheNamespace() else null
            latestDvrCapabilityRevision = null
            retryDisposition = null
        }
        worker = scope.launch(start = CoroutineStart.LAZY) {
            connectionLoop(profile, token)
        }.also(Job::start)
    }

    /**
     * Restores display-only metadata beside connecting and starts persisting publications.
     *
     * A same-profile reconnect already retains its snapshots, so restoration is skipped and the
     * writer simply resumes. Restore failures are nonfatal; cancellation still propagates.
     */
    private suspend fun prepareCache(
        namespace: CacheNamespace,
        restore: Boolean,
        accept: (RestoredSnapshots) -> Unit,
    ) {
        val runtime = cacheRuntime ?: return
        try {
            if (restore) runtime.restore(namespace, accept)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Persistence is optional. A failed restore must not fail connecting or the writer.
        }
    }

    /** Captures acceptance authority independently of the restore child's cancellation. */
    internal fun cacheRestoreAcceptance(
        namespace: CacheNamespace,
    ): (RestoredSnapshots) -> Unit {
        val (token, revision) = synchronized(stateLock) { activeToken to connectionAttemptRevision }
        return { restored ->
            // Snapshot lazies are thread-safe and built before taking owner/metadata locks.
            restored.epgSnapshot?.snapshot?.prepareForObservation()
            synchronized(stateLock) {
                if (token != null && activeToken === token && !closed && artworkNamespace == namespace &&
                    (attemptGeneration == null || connectionAttemptRevision == revision)
                ) {
                    metadata.seedRetainedSnapshots(
                        restored.catalog, restored.epgSnapshot, attemptGeneration, restored.isCurrent,
                    )
                }
            }
        }
    }

    private suspend fun connectionLoop(profile: ServerProfile, token: SessionToken) {
        try {
            coroutineScope {
                if (cacheRuntime != null) {
                    synchronized(stateLock) { ++connectionAttemptRevision }
                    val restore = synchronized(stateLock) { metadata.canSeedRetainedSnapshots() }
                    val namespace = profile.cacheNamespace()
                    val accept = cacheRestoreAcceptance(namespace)
                    // Establish persistence before either connection work or restore-child
                    // dispatch: even immediate sync/teardown must have a writer to flush.
                    cacheRuntime.start(namespace, metadata.publishedSnapshots)
                    // Register the restore before sync can complete, so a guide decoded after
                    // Ready still installs carryover; completion ends it even if never started.
                    val restoreTicket = if (restore) metadata.beginRetainedSnapshotRestore() else null
                    val restoring = launch { prepareCache(namespace, restore, accept) }
                    restoreTicket?.let { ticket ->
                        restoring.invokeOnCompletion { metadata.finishRetainedSnapshotRestore(ticket) }
                    }
                }
                connectionLoopBody(profile, token)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            commitUnavailable(
                token,
                SessionFailure.UNEXPECTED_FAILURE,
                SessionFailure.UNEXPECTED_FAILURE.recoveryDisposition,
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
                    failure = SessionFailure.UNEXPECTED_FAILURE,
                    disposition = SessionFailure.UNEXPECTED_FAILURE.recoveryDisposition,
                    connected = true,
                    reachedReady = false,
                )
            }

            if (outcome.reachedReady && outcome.failure != SessionFailure.METADATA_OVERFLOW) {
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
                            {
                                metadata.resetWorkingStateRetainingPublishedSnapshot()
                                Unit
                            },
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

        var runtimeFailure: kotlinx.coroutines.Deferred<GatewayConnectionFailure>? = null
        var metadataWorker: Job? = null
        try {
            val connection = when (val result = gateway.connect(profile.toGatewayConfiguration())) {
                is GatewayConnectResult.Connected -> result.connection
                is GatewayConnectResult.Failed -> return@coroutineScope result.failure.toAttemptOutcome()
            }
            runtimeFailure = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.awaitConnectionFailure(connection.generation)
            }
            requireCurrent(token)
            dvrMutations.bindGeneration(connection.generation)
            dvrProgress.bindGeneration(connection.generation, connection.protocolVersion)
            children.bindGeneration(connection.generation)
            synchronized(stateLock) {
                requireCurrent(token)
                attemptGeneration = connection.generation
                metadata.bindGeneration(connection.generation)
            }
            metadata.applyDvrAccess(connection.generation, connection.dvrAccess)
            metadata.publishServerFacts(connection.generation, connection.serverFacts)
            metadataWorker = launch(start = CoroutineStart.UNDISPATCHED) {
                drainMetadata(gateway.metadataBatches, metadata)
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
                ).withRetirementFailure(connection.generation)
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
                    .withRetirementFailure(connection.generation)
                is SynchronizationOutcome.ConnectionFailed -> {
                    synchronization.cancelAndJoin()
                    return@coroutineScope synchronizationOutcome.failure.toAttemptOutcome(
                        connected = true,
                    ).withRetirementFailure(connection.generation)
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
                ).withRetirementFailure(connection.generation)
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
                ).withRetirementFailure(connection.generation)
            }
            if (!children.startBackgroundEnrichment(connection.generation)) {
                return@coroutineScope GatewayConnectionFailure.TRANSPORT_UNAVAILABLE.toAttemptOutcome(
                    connected = true,
                    reachedReady = true,
                ).withRetirementFailure(connection.generation)
            }
            runtimeFailure.await().toAttemptOutcome(
                connected = true,
                reachedReady = true,
            ).withRetirementFailure(connection.generation)
        } finally {
            metadataWorker?.cancelAndJoin()
            runtimeFailure?.cancelAndJoin()
        }
    }

    private suspend fun AttemptOutcome.withRetirementFailure(generation: GatewayGeneration): AttemptOutcome =
        // The termination watcher can still be queued when admission observes retirement.
        gateway.retirementFailure(generation)?.toAttemptOutcome(connected, reachedReady) ?: this

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
            is GatewayResult.ServerRejected -> result.toSynchronizationFailure()
            GatewayResult.AccessDenied -> GatewayResult.AccessDenied.toSynchronizationFailure()
            GatewayResult.ConnectionLimit -> GatewayResult.ConnectionLimit.toSynchronizationFailure()
            GatewayResult.Timeout -> GatewayResult.Timeout.toSynchronizationFailure()
            GatewayResult.TransportUnavailable ->
                GatewayResult.TransportUnavailable.toSynchronizationFailure()
            GatewayResult.NotSupported -> GatewayResult.NotSupported.toSynchronizationFailure()
            GatewayResult.ObservationExpired -> GatewayResult.ObservationExpired.toSynchronizationFailure()
            GatewayResult.MalformedReply -> GatewayResult.MalformedReply.toSynchronizationFailure()
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
            connectionAttemptRevision += 1
            attemptGeneration = null
            activeGeneration = null
            latestDvrCapabilityRevision = null
            retryDisposition = disposition
            var admissionFailure = captureFailure { dvrMutations.stopAdmission(connectionRetired = true) }
            admissionFailure = captureFailure(admissionFailure) { dvrProgress.stopAdmission(connectionRetired = true) }
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
    internal val retiredPublication: PublishedSnapshots?,
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
        internal val retiredPublication: PublishedSnapshots?,
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
        GatewayConnectionFailure.AUTHENTICATION_REJECTED -> SessionFailure.AUTHENTICATION_REJECTED
        GatewayConnectionFailure.SERVER_UNREACHABLE -> SessionFailure.SERVER_UNREACHABLE
        GatewayConnectionFailure.NETWORK_UNAVAILABLE -> SessionFailure.NETWORK_UNAVAILABLE
        GatewayConnectionFailure.INCOMPATIBLE_SERVER -> SessionFailure.INCOMPATIBLE_SERVER
        GatewayConnectionFailure.UNSUPPORTED_SERVER_VERSION -> SessionFailure.SERVER_UPGRADE_REQUIRED
        GatewayConnectionFailure.METADATA_OVERFLOW -> SessionFailure.METADATA_OVERFLOW
        GatewayConnectionFailure.TRANSPORT_UNAVAILABLE -> SessionFailure.TRANSPORT_UNAVAILABLE
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
    is GatewayResult.ServerRejected -> synchronizationFailure(TvheadendFailure.SERVER_REJECTED)
    GatewayResult.AccessDenied -> synchronizationFailure(TvheadendFailure.ACCESS_DENIED)
    GatewayResult.ConnectionLimit -> synchronizationFailure(TvheadendFailure.CONNECTION_LIMIT)
    GatewayResult.Timeout -> synchronizationFailure(TvheadendFailure.TIMEOUT)
    GatewayResult.TransportUnavailable ->
        synchronizationFailure(TvheadendFailure.TRANSPORT_UNAVAILABLE)
    GatewayResult.NotSupported -> synchronizationFailure(TvheadendFailure.NOT_SUPPORTED)
    GatewayResult.ObservationExpired -> synchronizationFailure(TvheadendFailure.OBSERVATION_EXPIRED)
    GatewayResult.MalformedReply -> synchronizationFailure(TvheadendFailure.MALFORMED_RESPONSE)
}

private fun synchronizationFailure(
    failure: TvheadendFailure,
): SynchronizationOutcome.Failed = SynchronizationOutcome.Failed(
    AttemptOutcome(
        failure = SessionFailure.synchronizationFailed(failure),
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

/**
 * Reduces metadata as it arrives but publishes EPG traffic once per drained burst. Guide updates
 * arrive continuously from broadcast EIT, and each publication rebuilds the full EPG snapshot, so
 * publishing per message starves the rest of the process on small devices.
 *
 * Collection starts inline before async-enable. HTSP already owns an event/byte-bounded queue
 * per collector; adding a second queue duplicates buffering and coroutine handoffs. Consume
 * each borrowed batch directly, splitting only for publication, sync and generation fences.
 */
internal suspend fun drainMetadata(
    events: Flow<List<MetadataEvent>>,
    metadata: SessionMetadata,
    burstLimit: Int = METADATA_BURST_LIMIT,
) {
    require(burstLimit in 1..METADATA_BURST_LIMIT)
    events.collect { batch ->
        var start = 0
        while (start < batch.size) {
            var end = start
            while (end < batch.size && end - start < burstLimit &&
                batch[end].generation === batch[start].generation
            ) {
                if (batch[end++] is MetadataEvent.InitialSyncCompleted) break
            }
            currentCoroutineContext().ensureActive()
            metadata.acceptMetadataBatch(if (start == 0 && end == batch.size) batch else batch.subList(start, end))
            metadata.flushDeferredMetadata()
            start = end
        }
    }
}

// One publication at least every this many drained events under continuous traffic.
private const val METADATA_BURST_LIMIT = 512
