@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrResumeOffer
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationFailure
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekInvalidation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Whether recording playback starts at zero or requests the current server resume position. */
public enum class RecordingPlaybackStart {
    START_OVER,
    /** Completed recordings may resume; active recordings return a typed unsupported outcome. */
    RESUME,
}

/** Optional stream profile and requested timeshift buffer for one live target. */
public class LivePlaybackOptions(
    public val streamProfileId: StreamProfileId? = null,
    public val timeshiftPeriod: Duration = Duration.ZERO,
) {
    internal val subscriptionOptions: SubscriptionOptions = SubscriptionOptions(
        streamProfileUuid = streamProfileId?.value,
        timeshiftPeriod = timeshiftPeriod,
    )

    override fun toString(): String = "LivePlaybackOptions(<redacted>)"
}

/** Typed outcome of installing a live or completed-recording playback target. */
public enum class PlaybackTargetResult {
    STARTED,
    NOT_RUNNING,
    SHUT_DOWN,
    NOT_READY,
    RECORDING_PROGRESS_UNSUPPORTED,
    TARGET_UNAVAILABLE,
    /** An active recording was requested with server-progress resume instead of explicit start-over. */
    GROWING_RECORDING_RESUME_UNSUPPORTED,
    /** The active recording is outside the supported single-file pass-through MPEG-TS path. */
    GROWING_RECORDING_DEFERRED,
    PLAYER_UNAVAILABLE,
}

/** Typed outcome of retiring the coordinator's current target. */
public enum class PlaybackStopResult {
    STOPPED,
    ALREADY_STOPPED,
    NOT_RUNNING,
    SHUT_DOWN,
    PLAYER_UNAVAILABLE,
}

/** Typed outcome of terminal coordinator shutdown and its best-effort progress drain. */
public enum class PlaybackShutdownResult {
    DRAINED,
    TIMED_OUT,
    NOT_RUNNING,
    ALREADY_SHUT_DOWN,
    PLAYER_UNAVAILABLE,
}

/**
 * Serializes TVHeadend source changes on an application-owned [ExoPlayer].
 *
 * The application launches the one-shot [run] boundary and retains ownership of the player. This
 * coordinator never constructs or releases it, never changes autoplay, and owns no MediaSession,
 * service, audio focus, notification, surface, navigation, or presentation policy. Target, stop,
 * and shutdown intents may be called from any coroutine. All player work is moved to the player's
 * application looper, and cancellation before that work is claimed prevents player mutation.
 */
public class TvheadendPlaybackCoordinator internal constructor(
    private val environment: PlaybackCoordinatorEnvironment,
    private val player: PlaybackCoordinatorPlayer,
    private val playerEvents: PlaybackPlayerEventAccumulator,
    private val progressPolicy: DvrProgressPolicy,
    private val onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    private val timeSource: PlaybackCoordinatorTimeSource,
) {
    private val lifecycle = AtomicReference(CoordinatorLifecycle.NEW)
    private val commands = Channel<CoordinatorCommand>(capacity = COMMAND_CAPACITY)
    private val activeTimeshiftToken = AtomicReference<PlaybackTargetToken?>(null)
    private val mutableTimeshiftState = MutableStateFlow<LiveTimeshiftState>(
        LiveTimeshiftState.Unavailable,
    )
    private val mutableSubscriptionIssue = MutableStateFlow<SubscriptionIssue?>(null)

    /** Current app-safe timeshift state for the active live target. */
    public val timeshiftState: StateFlow<LiveTimeshiftState> = mutableTimeshiftState.asStateFlow()

    /** Canonical issue reported for the current live target, or null when none is current. */
    public val subscriptionIssue: StateFlow<SubscriptionIssue?> =
        mutableSubscriptionIssue.asStateFlow()

    /** Runs the coordinator until [shutdown] completes or the caller cancels this boundary. */
    public suspend fun run() {
        check(lifecycle.compareAndSet(CoordinatorLifecycle.NEW, CoordinatorLifecycle.RUNNING)) {
            "Playback coordinator run is one-shot"
        }
        try {
            CoordinatorActor(
                environment = environment,
                player = player,
                playerEvents = playerEvents,
                commands = commands,
                progressPolicy = progressPolicy,
                onRecoveryRequired = onRecoveryRequired,
                timeSource = timeSource,
                onShutdownClaimed = { lifecycle.set(CoordinatorLifecycle.SHUTTING_DOWN) },
                publishTimeshiftState = ::publishTimeshiftState,
                publishSubscriptionIssue = ::publishSubscriptionIssue,
                activateTimeshift = ::activateTimeshift,
                deactivateTimeshift = ::deactivateTimeshift,
            ).run()
        } finally {
            withContext(NonCancellable) { player.abandon() }
            playerEvents.discard()
            lifecycle.set(CoordinatorLifecycle.STOPPED)
            commands.close()
            rejectQueuedCommands()
        }
    }

    /** Replaces the current target with live playback for [channelId]. */
    public suspend fun setLiveTarget(channelId: ChannelId): PlaybackTargetResult =
        setLiveTarget(channelId, LivePlaybackOptions())

    /** Replaces the current live target with explicit profile and timeshift [options]. */
    public suspend fun setLiveTarget(
        channelId: ChannelId,
        options: LivePlaybackOptions,
    ): PlaybackTargetResult {
        val reply = CompletableDeferred<PlaybackTargetResult>()
        val command = CoordinatorCommand.Live(
            channelId = SubscriptionChannelId(channelId.value),
            options = options.subscriptionOptions,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.targetUnavailableResult() }
    }

    /**
     * Replaces the current target with a current completed or supported growing recording.
     *
     * Unknown and unsupported recording-progress capability fail before source creation. Growing
     * playback requires an explicit [RecordingPlaybackStart.START_OVER], one stable `.ts` file,
     * and starts at zero; saved server progress is never reinterpreted as a growing seek.
     */
    public suspend fun setRecordingTarget(
        recordingId: DvrEntryId,
        start: RecordingPlaybackStart = RecordingPlaybackStart.RESUME,
    ): PlaybackTargetResult {
        val reply = CompletableDeferred<PlaybackTargetResult>()
        val command = CoordinatorCommand.Recording(
            recordingId = RecordingId(recordingId.value),
            start = start,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.targetUnavailableResult() }
    }

    /** Rewinds or advances the current live timeshift target by signed [offset]. */
    public suspend fun seekTimeshift(offset: Duration): TimeshiftCommandResult {
        val reply = CompletableDeferred<TimeshiftCommandResult>()
        val command = CoordinatorCommand.TimeshiftSeek(
            target = SubscriptionSeekTarget.Relative(offset),
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.timeshiftUnavailableResult() }
    }

    /** Requests the existing bounded near-live target for the current live subscription. */
    public suspend fun returnToLive(): TimeshiftCommandResult {
        val reply = CompletableDeferred<TimeshiftCommandResult>()
        val command = CoordinatorCommand.TimeshiftSeek(
            target = SubscriptionSeekTarget.Live,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.timeshiftUnavailableResult() }
    }

    /** Pauses server delivery without changing the application-owned player's play state. */
    public suspend fun pauseTimeshift(): TimeshiftCommandResult = setTimeshiftSpeed(PAUSED_SPEED)

    /** Resumes normal server delivery without changing the application-owned player's play state. */
    public suspend fun resumeTimeshift(): TimeshiftCommandResult = setTimeshiftSpeed(NORMAL_SPEED)

    /** Retires and clears only the target installed by this coordinator. */
    public suspend fun stop(): PlaybackStopResult {
        val reply = CompletableDeferred<PlaybackStopResult>()
        val command = CoordinatorCommand.Stop(PlayerOperationTicket(), reply)
        return submit(command, reply) { state -> state.stopUnavailableResult() }
    }

    /**
     * Retires the current target and drains at most one pending progress report for [drainTimeout].
     *
     * After this returns, join [run], then shut down the session and release the player. The timeout
     * must be finite, non-negative, and no greater than ten seconds.
     */
    public suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult {
        require(
            drainTimeout.isFinite() &&
                !drainTimeout.isNegative() &&
                drainTimeout <= MAX_DRAIN_TIMEOUT,
        ) {
            "Playback shutdown drain timeout must be finite and between zero and ten seconds"
        }
        val reply = CompletableDeferred<PlaybackShutdownResult>()
        val command = CoordinatorCommand.Shutdown(
            drainTimeout = drainTimeout,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.shutdownUnavailableResult() }
    }

    private suspend fun <R> submit(
        command: CoordinatorCommand,
        reply: CompletableDeferred<R>,
        unavailable: (CoordinatorLifecycle) -> R,
    ): R {
        val initial = lifecycle.get()
        if (initial != CoordinatorLifecycle.RUNNING) return unavailable(initial)
        try {
            commands.send(command)
        } catch (_: ClosedSendChannelException) {
            return unavailable(lifecycle.get())
        } catch (cancellation: CancellationException) {
            command.ticket.cancel()
            throw cancellation
        }
        return try {
            reply.await()
        } catch (cancellation: CancellationException) {
            command.ticket.cancel()
            throw cancellation
        }
    }

    private suspend fun setTimeshiftSpeed(speed: Int): TimeshiftCommandResult {
        val reply = CompletableDeferred<TimeshiftCommandResult>()
        val command = CoordinatorCommand.TimeshiftSpeed(
            speed = speed,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.timeshiftUnavailableResult() }
    }

    private fun publishTimeshiftState(
        token: PlaybackTargetToken,
        state: LiveTimeshiftState,
    ) {
        if (activeTimeshiftToken.get() === token) mutableTimeshiftState.value = state
    }

    private fun publishSubscriptionIssue(
        token: PlaybackTargetToken,
        issue: SubscriptionIssue?,
    ) {
        if (activeTimeshiftToken.get() === token) mutableSubscriptionIssue.value = issue
    }

    private fun activateTimeshift(
        token: PlaybackTargetToken,
        bridge: LiveTimeshiftControlBridge,
    ) {
        activeTimeshiftToken.set(token)
        bridge.publishCurrent()
    }

    private fun deactivateTimeshift(token: PlaybackTargetToken) {
        if (activeTimeshiftToken.compareAndSet(token, null)) {
            mutableTimeshiftState.value = LiveTimeshiftState.Unavailable
            mutableSubscriptionIssue.value = null
        }
    }

    private fun rejectQueuedCommands() {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: return
            command.rejectAfterShutdown()
        }
    }

    private companion object {
        const val COMMAND_CAPACITY: Int = 32
        val MAX_DRAIN_TIMEOUT: Duration = 10.seconds
    }
}

/** Creates a narrow playback coordinator over [session] and an application-owned [player]. */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendPlaybackCoordinator(
    session: TvheadendSession,
    player: ExoPlayer,
    progressPolicy: DvrProgressPolicy = DvrProgressPolicy(),
    recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit = {},
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): TvheadendPlaybackCoordinator {
    val environment = SessionPlaybackCoordinatorEnvironment(session, progressPolicy)
    val events = PlaybackPlayerEventAccumulator()
    return TvheadendPlaybackCoordinator(
        environment = environment,
        player = Media3PlaybackCoordinatorPlayer(
            access = ExoPlayerCoordinatorPlaybackAccess(
                player = player,
                subscriptions = session.subscriptions,
                recordings = session.recordings,
                recoveryPolicy = recoveryPolicy,
                onUnsupportedStream = onUnsupportedStream,
            ),
            events = events,
            admitRecording = environment::admitRecording,
        ),
        playerEvents = events,
        progressPolicy = progressPolicy,
        onRecoveryRequired = onRecoveryRequired,
        timeSource = SystemPlaybackCoordinatorTimeSource,
    )
}

internal interface PlaybackCoordinatorEnvironment {
    val observation: StateFlow<SessionObservation>

    fun admitRecording(
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): RecordingAdmission

    suspend fun reportProgress(
        recordingId: DvrEntryId,
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    )
}

private class SessionPlaybackCoordinatorEnvironment(
    private val session: TvheadendSession,
    private val progressPolicy: DvrProgressPolicy,
) : PlaybackCoordinatorEnvironment {
    override val observation: StateFlow<SessionObservation> = session.observation

    override fun admitRecording(
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): RecordingAdmission = admitRecordingTarget(
        observation = observation,
        recordingId = recordingId,
        start = start,
        progressPolicy = progressPolicy,
        bindGrowingRecording = session.recordings::bindGrowingRecording,
    )

    override suspend fun reportProgress(
        recordingId: DvrEntryId,
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ) {
        if (growingLease == null) {
            session.dvrRepository.reportProgress(recordingId, progress)
        } else {
            session.dvrRepository.reportProgress(growingLease, progress)
        }
    }
}

internal fun admitRecordingTarget(
    observation: StateFlow<SessionObservation>,
    recordingId: RecordingId,
    start: RecordingPlaybackStart,
    progressPolicy: DvrProgressPolicy,
    bindGrowingRecording: (RecordingId) -> RecordingFileResult<GrowingRecordingFileLease>,
): RecordingAdmission {
    val initial = observation.value
    if (initial.sessionState !is SessionState.Ready) return RecordingAdmission.NotReady
    when (initial.recordingProgressCapability) {
        RecordingProgressCapability.UNKNOWN -> return RecordingAdmission.NotReady
        RecordingProgressCapability.UNSUPPORTED -> return RecordingAdmission.ProgressUnsupported
        RecordingProgressCapability.SUPPORTED -> Unit
    }
    val growingBinding = when (start) {
        RecordingPlaybackStart.START_OVER -> bindGrowingRecording(recordingId)
        RecordingPlaybackStart.RESUME -> null
    }
    val observed = observation.value
    if (observed.sessionState !is SessionState.Ready) return RecordingAdmission.NotReady
    when (observed.recordingProgressCapability) {
        RecordingProgressCapability.UNKNOWN -> return RecordingAdmission.NotReady
        RecordingProgressCapability.UNSUPPORTED -> return RecordingAdmission.ProgressUnsupported
        RecordingProgressCapability.SUPPORTED -> Unit
    }
    val current = observed.dvrState as? DvrRepositoryState.Current
        ?: return RecordingAdmission.NotReady
    val entry = current.snapshot.entries.singleOrNull { candidate ->
        candidate.id.value == recordingId.value
    }
        ?: return RecordingAdmission.TargetUnavailable
    return when (entry.state) {
        DvrEntryState.RECORDING -> admitGrowingRecording(
            entry = entry,
            observation = observation,
            start = start,
            binding = growingBinding,
        )
        DvrEntryState.COMPLETED -> RecordingAdmission.Completed(
            resumePosition = when (start) {
                RecordingPlaybackStart.START_OVER -> null
                RecordingPlaybackStart.RESUME -> when (val offer = progressPolicy.resumeOffer(entry)) {
                    is DvrResumeOffer.Resume -> offer.position
                    DvrResumeOffer.StartOver -> null
                }
            },
        )
        DvrEntryState.SCHEDULED,
        DvrEntryState.MISSED,
        DvrEntryState.INVALID,
        DvrEntryState.RECORDING_ERROR,
        DvrEntryState.COMPLETED_ERROR,
        DvrEntryState.FILE_MISSING,
        DvrEntryState.UNKNOWN,
        null,
        -> RecordingAdmission.TargetUnavailable
    }
}

private fun admitGrowingRecording(
    entry: DvrEntry,
    observation: StateFlow<SessionObservation>,
    start: RecordingPlaybackStart,
    binding: RecordingFileResult<GrowingRecordingFileLease>?,
): RecordingAdmission {
    val file = entry.files?.singleOrNull() ?: return RecordingAdmission.TargetUnavailable
    val containerPath = file.path ?: entry.path ?: return RecordingAdmission.TargetUnavailable
    if (containerPath.isBlank()) return RecordingAdmission.TargetUnavailable
    if (!containerPath.endsWith(".ts", ignoreCase = true)) {
        return RecordingAdmission.GrowingRecordingDeferred
    }
    val fence = GrowingRecordingFence.create(entry, observation)
        ?: return RecordingAdmission.TargetUnavailable
    return when (start) {
        RecordingPlaybackStart.START_OVER -> when (
            val acceptedBinding = checkNotNull(binding)
        ) {
            is RecordingFileResult.Ok -> {
                val lease = acceptedBinding.value
                if (
                    !lease.isCurrent ||
                    fence.observe() == GrowingRecordingObservation.INVALID
                ) {
                    RecordingAdmission.TargetUnavailable
                } else {
                    RecordingAdmission.Growing(fence, lease)
                }
            }
            is RecordingFileResult.Failed -> when (acceptedBinding.failure) {
                RecordingFileFailure.CONNECTION_CHANGED -> RecordingAdmission.NotReady
                RecordingFileFailure.ACCESS_DENIED,
                RecordingFileFailure.FILE_UNAVAILABLE,
                RecordingFileFailure.CONNECTION_LIMIT,
                RecordingFileFailure.TIMEOUT,
                RecordingFileFailure.NOT_SUPPORTED,
                -> RecordingAdmission.TargetUnavailable
            }
        }
        RecordingPlaybackStart.RESUME -> RecordingAdmission.GrowingResumeUnsupported
    }
}

private class CoordinatorActor(
    private val environment: PlaybackCoordinatorEnvironment,
    private val player: PlaybackCoordinatorPlayer,
    private val playerEvents: PlaybackPlayerEventAccumulator,
    private val commands: Channel<CoordinatorCommand>,
    private val progressPolicy: DvrProgressPolicy,
    private val onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    private val timeSource: PlaybackCoordinatorTimeSource,
    private val onShutdownClaimed: () -> Unit,
    private val publishTimeshiftState: (PlaybackTargetToken, LiveTimeshiftState) -> Unit,
    private val publishSubscriptionIssue: (PlaybackTargetToken, SubscriptionIssue?) -> Unit,
    private val activateTimeshift: (PlaybackTargetToken, LiveTimeshiftControlBridge) -> Unit,
    private val deactivateTimeshift: (PlaybackTargetToken) -> Unit,
) {
    private val mailbox = PlaybackProgressMailbox()
    private val gateEvents = Channel<Boolean>(capacity = 16)
    private val ticks = Channel<PlaybackTargetToken>(Channel.CONFLATED)
    @Volatile
    private var reportGate: ReportingGateEpoch? = null
    private var activeTarget: ActorTarget? = null
    private var ticker: Job? = null

    suspend fun run(): Unit = coroutineScope {
        val reportWorker = launch { runReportWorker() }
        val gateObserver = launch {
            environment.observation.collect { observation ->
                val valid = observation.sessionState is SessionState.Ready &&
                    observation.recordingProgressCapability == RecordingProgressCapability.SUPPORTED
                if (!valid) reportGate?.invalidate()
                gateEvents.send(valid)
            }
        }
        var processing: CoordinatorCommand? = null
        try {
            applyReportGate(currentReportGateIsValid(), this)
            var shuttingDown = false
            while (!shuttingDown) {
                select<Unit> {
                    commands.onReceiveCatching { received ->
                        val command = received.getOrNull() ?: return@onReceiveCatching
                        drainGateEvents(this@coroutineScope)
                        if (command.ticket.isCancelled()) return@onReceiveCatching
                        processing = command
                        shuttingDown = processCommand(command, reportWorker, this@coroutineScope)
                        processing = null
                    }
                    playerEvents.signal.onReceive {
                        drainPlayerEvents()
                    }
                    gateEvents.onReceive { valid ->
                        applyReportGate(valid, this@coroutineScope)
                    }
                    ticks.onReceive { token ->
                        observeElapsed(token)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            processing?.rejectAfterShutdown()
            throw cancellation
        } catch (failure: Exception) {
            processing?.rejectAfterShutdown()
            throw failure
        } finally {
            (activeTarget as? ActorTarget.Live)?.let { target ->
                target.timeshiftControls.retire()
                deactivateTimeshift(target.token)
            }
            reportGate?.invalidate()
            reportGate = null
            ticker?.cancel()
            ticker = null
            (activeTarget as? ActorTarget.Recording)?.reportEpoch?.invalidate()
            mailbox.discard()
            reportWorker.cancelAndJoin()
            gateObserver.cancelAndJoin()
        }
    }

    private suspend fun processCommand(
        command: CoordinatorCommand,
        reportWorker: Job,
        scope: CoroutineScope,
    ): Boolean = when (command) {
        is CoordinatorCommand.Live -> {
            val token = PlaybackTargetToken()
            val timeshiftControls = LiveTimeshiftControlBridge(
                token = token,
                publish = { state -> publishTimeshiftState(token, state) },
                publishIssue = { issue -> publishSubscriptionIssue(token, issue) },
            )
            val result = player.installLive(
                ticket = command.ticket,
                token = token,
                channelId = command.channelId,
                options = command.options,
                timeshiftControls = timeshiftControls,
            )
            applyRetirement(result.retiredTarget, result.retiredRecording)
            if (result.status == PlaybackPlayerInstallStatus.STARTED) {
                ticker?.cancel()
                activeTarget = ActorTarget.Live(token, timeshiftControls)
                activateTimeshift(token, timeshiftControls)
            } else {
                token.retire()
                timeshiftControls.retire()
            }
            if (result.status != PlaybackPlayerInstallStatus.CANCELLED) {
                command.reply.complete(result.status.toPublicTargetResult())
            }
            false
        }
        is CoordinatorCommand.TimeshiftSeek -> {
            processTimeshiftCommand(command) { controls ->
                controls.seek(command.target)?.toPublicTimeshiftResult()
                    ?: TimeshiftCommandResult.UNAVAILABLE
            }
            false
        }
        is CoordinatorCommand.TimeshiftSpeed -> {
            processTimeshiftCommand(command) { controls ->
                controls.setSpeed(command.speed)?.toPublicTimeshiftResult()
                    ?: TimeshiftCommandResult.UNAVAILABLE
            }
            false
        }
        is CoordinatorCommand.Recording -> {
            val token = PlaybackTargetToken()
            val result = player.installRecording(
                ticket = command.ticket,
                token = token,
                recordingId = command.recordingId,
                start = command.start,
            )
            applyRetirement(result.retiredTarget, result.retiredRecording)
            if (result.status == PlaybackPlayerInstallStatus.STARTED) {
                val admission = checkNotNull(result.installedRecording)
                val target = ActorTarget.Recording(
                    token = token,
                    recordingId = DvrEntryId(command.recordingId.value),
                    admission = admission,
                )
                activeTarget = target
                applyReportGate(currentReportGateIsValid(), scope)
                if (target.reportEpoch == null) startReporting(target, scope)
            } else {
                token.retire()
            }
            if (result.status != PlaybackPlayerInstallStatus.CANCELLED) {
                command.reply.complete(result.status.toPublicTargetResult())
            }
            false
        }
        is CoordinatorCommand.Stop -> {
            val result = player.stop(command.ticket)
            applyRetirement(result.retiredTarget, result.retiredRecording)
            if (!result.cancelled) {
                command.reply.complete(
                    when {
                        !result.playerAvailable -> PlaybackStopResult.PLAYER_UNAVAILABLE
                        result.retiredTarget -> PlaybackStopResult.STOPPED
                        else -> PlaybackStopResult.ALREADY_STOPPED
                    },
                )
            }
            false
        }
        is CoordinatorCommand.Shutdown -> {
            val result = player.stop(command.ticket)
            if (result.cancelled) return false
            onShutdownClaimed()
            applyRetirement(result.retiredTarget, result.retiredRecording)
            mailbox.seal()
            val drained = reportWorker.isCompleted || withTimeoutOrNull(command.drainTimeout) {
                reportWorker.join()
                true
            } == true
            if (!drained) reportWorker.cancelAndJoin()
            command.reply.complete(
                when {
                    !result.playerAvailable -> PlaybackShutdownResult.PLAYER_UNAVAILABLE
                    drained -> PlaybackShutdownResult.DRAINED
                    else -> PlaybackShutdownResult.TIMED_OUT
                },
            )
            rejectRemainingCommands()
            true
        }
    }

    private suspend fun processTimeshiftCommand(
        command: CoordinatorCommand.Timeshift,
        operation: suspend (LiveTimeshiftControlBridge) -> TimeshiftCommandResult,
    ) {
        if (!command.ticket.claim()) return
        val target = activeTarget as? ActorTarget.Live
        try {
            val result = target?.takeIf { it.token.isActive() }
                ?.let { live -> operation(live.timeshiftControls) }
                ?: TimeshiftCommandResult.UNAVAILABLE
            command.ticket.complete()
            command.reply.complete(result)
        } catch (cancellation: CancellationException) {
            command.ticket.complete()
            command.reply.completeExceptionally(cancellation)
            throw cancellation
        }
    }

    private suspend fun applyRetirement(
        retiredTarget: Boolean,
        retiredRecording: RetiredRecordingTarget?,
    ) {
        if (!retiredTarget) return
        val old = activeTarget
        if (old is ActorTarget.Live) {
            old.timeshiftControls.retire()
            deactivateTimeshift(old.token)
        }
        if (old is ActorTarget.Recording && retiredRecording?.token === old.token) {
            terminalize(
                target = old,
                snapshot = retiredRecording.snapshot,
                exit = retiredRecording.exit,
                growingFinalEndProven = retiredRecording.growingFinalEndProven,
            )
        }
        ticker?.cancel()
        ticker = null
        activeTarget = null
    }

    private suspend fun drainPlayerEvents() {
        while (true) {
            val event = playerEvents.take() ?: return
            val target = activeTarget ?: continue
            if (target.token !== event.token || !event.token.isActive()) continue
            when (target) {
                is ActorTarget.Live -> event.recoveryReason?.let { reason ->
                    try {
                        onRecoveryRequired(reason)
                    } catch (_: Exception) {
                        // Application callbacks must not terminate the coordinator owner.
                    }
                }
                is ActorTarget.Recording -> when {
                    event.terminalExit != null -> terminalize(
                        target,
                        event.snapshot,
                        event.terminalExit,
                        event.growingFinalEndProven,
                    )
                    event.paused && !target.terminalized && refreshGrowingTarget(target) ->
                        target.tracker?.let { tracker ->
                            target.reportEpoch?.takeIf { epoch -> epoch.isValid() }?.let { epoch ->
                                mailbox.offer(
                                    PendingPlaybackProgress(
                                        epoch = epoch,
                                        recordingId = target.recordingId,
                                        growingLease = target.growingLease,
                                        progress = tracker.onPause(timeSource.now(), event.snapshot.position),
                                        terminal = false,
                                    ),
                                )
                            }
                        }
                }
            }
        }
    }

    private suspend fun observeElapsed(token: PlaybackTargetToken) {
        val target = activeTarget as? ActorTarget.Recording ?: return
        if (target.token !== token || target.terminalized) return
        if (!refreshGrowingTarget(target)) return
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val snapshot = player.snapshot(token) ?: return
        tracker.onElapsed(timeSource.now(), snapshot.position)?.let { progress ->
            mailbox.offer(
                PendingPlaybackProgress(
                    epoch = epoch,
                    recordingId = target.recordingId,
                    growingLease = target.growingLease,
                    progress = progress,
                    terminal = false,
                ),
            )
        }
    }

    private suspend fun terminalize(
        target: ActorTarget.Recording,
        snapshot: PlaybackPlayerSnapshot,
        exit: DvrPlaybackExit,
        growingFinalEndProven: Boolean = false,
    ) {
        if (target.terminalized) return
        target.terminalized = true
        ticker?.cancel()
        ticker = null
        if (!refreshGrowingTarget(target)) return
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val terminalState = if (
            target.growingFence != null &&
            exit == DvrPlaybackExit.NATURAL_END &&
            !growingFinalEndProven
        ) {
            null
        } else {
            target.entryState
        }
        mailbox.offer(
            PendingPlaybackProgress(
                epoch = epoch,
                recordingId = target.recordingId,
                growingLease = target.growingLease,
                progress = tracker.onTerminal(
                    position = snapshot.position,
                    duration = snapshot.duration.takeIf { target.growingFence == null },
                    state = terminalState,
                    exit = exit,
                ),
                terminal = true,
            ),
        )
    }

    private suspend fun applyReportGate(valid: Boolean, scope: CoroutineScope) {
        if (!valid) {
            reportGate?.invalidate()
            reportGate = null
            val target = activeTarget as? ActorTarget.Recording
            target?.reportEpoch?.invalidate()
            target?.reportEpoch = null
            target?.tracker = null
            ticker?.cancel()
            ticker = null
            mailbox.discardInvalid()
            return
        }
        if (reportGate == null) reportGate = ReportingGateEpoch()
        val target = activeTarget as? ActorTarget.Recording ?: return
        if (!target.terminalized && target.reportEpoch == null) startReporting(target, scope)
    }

    private suspend fun startReporting(target: ActorTarget.Recording, scope: CoroutineScope) {
        val gate = reportGate?.takeIf { it.isValid() } ?: return
        if (!refreshGrowingTarget(target)) return
        val tracker = progressPolicy.tracker()
        target.tracker = tracker
        target.reportEpoch = PlaybackReportEpoch(gate) {
            target.growingLease?.isCurrent != false &&
                target.growingFence?.observe() != GrowingRecordingObservation.INVALID
        }
        player.snapshot(target.token)?.let { snapshot ->
            tracker.onElapsed(timeSource.now(), snapshot.position)
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                timeSource.wait(progressPolicy.checkpointInterval)
                ticks.send(target.token)
            }
        }
    }

    private suspend fun drainGateEvents(scope: CoroutineScope) {
        while (true) {
            val valid = gateEvents.tryReceive().getOrNull() ?: break
            applyReportGate(valid, scope)
        }
    }

    private fun currentReportGateIsValid(): Boolean {
        val observation = environment.observation.value
        return observation.sessionState is SessionState.Ready &&
            observation.recordingProgressCapability == RecordingProgressCapability.SUPPORTED
    }

    private fun refreshGrowingTarget(target: ActorTarget.Recording): Boolean {
        if (target.growingLease?.isCurrent == false) return invalidateGrowingTarget(target)
        return when (target.growingFence?.observe()) {
            null,
            GrowingRecordingObservation.RECORDING,
            -> true
            GrowingRecordingObservation.COMPLETED -> {
                target.entryState = DvrEntryState.COMPLETED
                true
            }
            GrowingRecordingObservation.INVALID -> invalidateGrowingTarget(target)
        }
    }

    private fun invalidateGrowingTarget(target: ActorTarget.Recording): Boolean {
        target.reportEpoch?.invalidate()
        target.reportEpoch = null
        target.tracker = null
        ticker?.cancel()
        ticker = null
        mailbox.discardInvalid()
        return false
    }

    private suspend fun runReportWorker() {
        while (true) {
            val report = mailbox.next() ?: return
            if (!report.epoch.isValid() || !currentReportGateIsValid()) continue
            try {
                environment.reportProgress(
                    report.recordingId,
                    report.growingLease,
                    report.progress,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Progress is best-effort and never controls playback lifetime.
            }
        }
    }

    private fun rejectRemainingCommands() {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: return
            command.rejectAfterShutdown()
        }
    }

    private sealed class ActorTarget(open val token: PlaybackTargetToken) {
        data class Live(
            override val token: PlaybackTargetToken,
            val timeshiftControls: LiveTimeshiftControlBridge,
        ) : ActorTarget(token)

        class Recording(
            override val token: PlaybackTargetToken,
            val recordingId: DvrEntryId,
            admission: RecordingAdmission.Accepted,
        ) : ActorTarget(token) {
            var entryState: DvrEntryState = when (admission) {
                is RecordingAdmission.Completed -> DvrEntryState.COMPLETED
                is RecordingAdmission.Growing -> DvrEntryState.RECORDING
            }
            val growingFence: GrowingRecordingFence? =
                (admission as? RecordingAdmission.Growing)?.fence
            val growingLease: GrowingRecordingFileLease? =
                (admission as? RecordingAdmission.Growing)?.lease
            var tracker: at.bernhardberger.tvheadend.sdk.core.DvrProgressTracker? = null
            var reportEpoch: PlaybackReportEpoch? = null
            var terminalized: Boolean = false
        }
    }
}

private sealed class CoordinatorCommand(
    open val ticket: PlayerOperationTicket,
) {
    data class Live(
        val channelId: SubscriptionChannelId,
        val options: SubscriptionOptions,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackTargetResult>,
    ) : CoordinatorCommand(ticket)

    data class Recording(
        val recordingId: RecordingId,
        val start: RecordingPlaybackStart,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackTargetResult>,
    ) : CoordinatorCommand(ticket)

    sealed class Timeshift(
        override val ticket: PlayerOperationTicket,
        open val reply: CompletableDeferred<TimeshiftCommandResult>,
    ) : CoordinatorCommand(ticket)

    data class TimeshiftSeek(
        val target: SubscriptionSeekTarget,
        override val ticket: PlayerOperationTicket,
        override val reply: CompletableDeferred<TimeshiftCommandResult>,
    ) : Timeshift(ticket, reply)

    data class TimeshiftSpeed(
        val speed: Int,
        override val ticket: PlayerOperationTicket,
        override val reply: CompletableDeferred<TimeshiftCommandResult>,
    ) : Timeshift(ticket, reply)

    data class Stop(
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackStopResult>,
    ) : CoordinatorCommand(ticket)

    data class Shutdown(
        val drainTimeout: Duration,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackShutdownResult>,
    ) : CoordinatorCommand(ticket)

    fun rejectAfterShutdown() {
        when (this) {
            is Live -> reply.complete(PlaybackTargetResult.SHUT_DOWN)
            is Recording -> reply.complete(PlaybackTargetResult.SHUT_DOWN)
            is TimeshiftSeek -> reply.complete(TimeshiftCommandResult.SHUT_DOWN)
            is TimeshiftSpeed -> reply.complete(TimeshiftCommandResult.SHUT_DOWN)
            is Stop -> reply.complete(PlaybackStopResult.SHUT_DOWN)
            is Shutdown -> reply.complete(PlaybackShutdownResult.ALREADY_SHUT_DOWN)
        }
    }
}

private enum class CoordinatorLifecycle {
    NEW,
    RUNNING,
    SHUTTING_DOWN,
    STOPPED,
}

private fun CoordinatorLifecycle.targetUnavailableResult(): PlaybackTargetResult = when (this) {
    CoordinatorLifecycle.NEW -> PlaybackTargetResult.NOT_RUNNING
    CoordinatorLifecycle.RUNNING -> PlaybackTargetResult.SHUT_DOWN
    CoordinatorLifecycle.SHUTTING_DOWN,
    CoordinatorLifecycle.STOPPED,
    -> PlaybackTargetResult.SHUT_DOWN
}

private fun CoordinatorLifecycle.stopUnavailableResult(): PlaybackStopResult = when (this) {
    CoordinatorLifecycle.NEW -> PlaybackStopResult.NOT_RUNNING
    CoordinatorLifecycle.RUNNING -> PlaybackStopResult.SHUT_DOWN
    CoordinatorLifecycle.SHUTTING_DOWN,
    CoordinatorLifecycle.STOPPED,
    -> PlaybackStopResult.SHUT_DOWN
}

private fun CoordinatorLifecycle.shutdownUnavailableResult(): PlaybackShutdownResult = when (this) {
    CoordinatorLifecycle.NEW -> PlaybackShutdownResult.NOT_RUNNING
    CoordinatorLifecycle.RUNNING -> PlaybackShutdownResult.ALREADY_SHUT_DOWN
    CoordinatorLifecycle.SHUTTING_DOWN,
    CoordinatorLifecycle.STOPPED,
    -> PlaybackShutdownResult.ALREADY_SHUT_DOWN
}

private fun CoordinatorLifecycle.timeshiftUnavailableResult(): TimeshiftCommandResult = when (this) {
    CoordinatorLifecycle.NEW -> TimeshiftCommandResult.NOT_RUNNING
    CoordinatorLifecycle.RUNNING,
    CoordinatorLifecycle.SHUTTING_DOWN,
    CoordinatorLifecycle.STOPPED,
    -> TimeshiftCommandResult.SHUT_DOWN
}

private fun SubscriptionSeekResult.toPublicTimeshiftResult(): TimeshiftCommandResult = when (this) {
    SubscriptionSeekResult.Accepted -> TimeshiftCommandResult.ACCEPTED
    SubscriptionSeekResult.Rejected -> TimeshiftCommandResult.REJECTED
    is SubscriptionSeekResult.Refused -> failure.toPublicTimeshiftResult()
    SubscriptionSeekResult.NotSeekable -> TimeshiftCommandResult.UNAVAILABLE
    SubscriptionSeekResult.AlreadyPending -> TimeshiftCommandResult.ALREADY_PENDING
    SubscriptionSeekResult.NotAcknowledged -> TimeshiftCommandResult.NOT_ACKNOWLEDGED
    is SubscriptionSeekResult.Invalidated -> when (cause) {
        SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT ->
            TimeshiftCommandResult.ACKNOWLEDGEMENT_TIMEOUT
        SubscriptionSeekInvalidation.PENDING_QUEUE_OVERFLOW ->
            TimeshiftCommandResult.PENDING_QUEUE_OVERFLOW
        SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME ->
            TimeshiftCommandResult.UNCERTAIN_REQUEST_OUTCOME
        SubscriptionSeekInvalidation.UNRECOGNIZED_ACKNOWLEDGEMENT ->
            TimeshiftCommandResult.UNRECOGNIZED_ACKNOWLEDGEMENT
        SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE ->
            TimeshiftCommandResult.RESUMED_SEGMENT_UNANCHORABLE
    }
    SubscriptionSeekResult.SubscriptionEnded -> TimeshiftCommandResult.SUBSCRIPTION_ENDED
}

private fun SubscriptionOperationResult<Unit>.toPublicTimeshiftResult(): TimeshiftCommandResult =
    when (this) {
        is SubscriptionOperationResult.Ok -> TimeshiftCommandResult.ACCEPTED
        SubscriptionOperationResult.ServerRejected -> TimeshiftCommandResult.SERVER_REJECTED
        SubscriptionOperationResult.AccessDenied -> TimeshiftCommandResult.ACCESS_DENIED
        SubscriptionOperationResult.ConnectionLimit -> TimeshiftCommandResult.CONNECTION_LIMIT
        SubscriptionOperationResult.Timeout -> TimeshiftCommandResult.TIMEOUT
        SubscriptionOperationResult.TransportUnavailable ->
            TimeshiftCommandResult.TRANSPORT_UNAVAILABLE
        SubscriptionOperationResult.NotSupported -> TimeshiftCommandResult.NOT_SUPPORTED
    }

private fun SubscriptionOperationFailure.toPublicTimeshiftResult(): TimeshiftCommandResult = when (this) {
    SubscriptionOperationFailure.SERVER_REJECTED -> TimeshiftCommandResult.SERVER_REJECTED
    SubscriptionOperationFailure.ACCESS_DENIED -> TimeshiftCommandResult.ACCESS_DENIED
    SubscriptionOperationFailure.CONNECTION_LIMIT -> TimeshiftCommandResult.CONNECTION_LIMIT
    SubscriptionOperationFailure.TIMEOUT -> TimeshiftCommandResult.TIMEOUT
    SubscriptionOperationFailure.TRANSPORT_UNAVAILABLE -> TimeshiftCommandResult.TRANSPORT_UNAVAILABLE
    SubscriptionOperationFailure.NOT_SUPPORTED -> TimeshiftCommandResult.NOT_SUPPORTED
}

private fun PlaybackPlayerInstallStatus.toPublicTargetResult(): PlaybackTargetResult = when (this) {
    PlaybackPlayerInstallStatus.STARTED -> PlaybackTargetResult.STARTED
    PlaybackPlayerInstallStatus.NOT_READY -> PlaybackTargetResult.NOT_READY
    PlaybackPlayerInstallStatus.RECORDING_PROGRESS_UNSUPPORTED ->
        PlaybackTargetResult.RECORDING_PROGRESS_UNSUPPORTED
    PlaybackPlayerInstallStatus.TARGET_UNAVAILABLE -> PlaybackTargetResult.TARGET_UNAVAILABLE
    PlaybackPlayerInstallStatus.GROWING_RECORDING_RESUME_UNSUPPORTED ->
        PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED
    PlaybackPlayerInstallStatus.GROWING_RECORDING_DEFERRED ->
        PlaybackTargetResult.GROWING_RECORDING_DEFERRED
    PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE -> PlaybackTargetResult.PLAYER_UNAVAILABLE
    PlaybackPlayerInstallStatus.CANCELLED -> error("Cancelled player operations have no result")
}
