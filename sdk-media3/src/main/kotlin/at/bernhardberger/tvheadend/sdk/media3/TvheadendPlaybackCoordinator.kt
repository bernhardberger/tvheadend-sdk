@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
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
    private val mutableLiveDiagnostics = MutableStateFlow<LiveSubscriptionDiagnostics?>(null)

    /** Current app-safe timeshift state for the active live target. */
    public val timeshiftState: StateFlow<LiveTimeshiftState> = mutableTimeshiftState.asStateFlow()

    /** Canonical issue reported for the current live target, or null when none is current. */
    public val subscriptionIssue: StateFlow<SubscriptionIssue?> =
        mutableSubscriptionIssue.asStateFlow()

    /** App-safe observations for the current live target, or null when none are available. */
    public val liveDiagnostics: StateFlow<LiveSubscriptionDiagnostics?> =
        mutableLiveDiagnostics.asStateFlow()

    /** Runs the coordinator until [shutdown] completes or the caller cancels this boundary. */
    public suspend fun run() {
        check(lifecycle.compareAndSet(CoordinatorLifecycle.NEW, CoordinatorLifecycle.RUNNING)) {
            "Playback coordinator run is one-shot"
        }
        try {
            CoordinatorActor(
                player = player,
                playerEvents = playerEvents,
                commands = commands,
                progressPolicy = progressPolicy,
                onRecoveryRequired = onRecoveryRequired,
                timeSource = timeSource,
                onShutdownClaimed = { lifecycle.set(CoordinatorLifecycle.SHUTTING_DOWN) },
                publishTimeshiftState = ::publishTimeshiftState,
                publishSubscriptionIssue = ::publishSubscriptionIssue,
                publishLiveDiagnostics = ::publishLiveDiagnostics,
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

    /** Replaces the current live target with the exact observation-bound [binding]. */
    public suspend fun setLiveTarget(
        binding: PlaybackBinding.Live,
        options: LivePlaybackOptions = LivePlaybackOptions(),
    ): PlaybackTargetResult = setLiveTarget(BoundCoordinatorLiveTarget(binding), options)

    internal suspend fun setLiveTarget(
        target: CoordinatorLiveTarget,
        options: LivePlaybackOptions = LivePlaybackOptions(),
    ): PlaybackTargetResult {
        val reply = CompletableDeferred<PlaybackTargetResult>()
        val command = CoordinatorCommand.Live(
            target = target,
            options = options.subscriptionOptions,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.targetUnavailableResult() }
    }

    /**
     * Replaces the current target with one exact observation-bound recording.
     *
     * Completed playback starts over when recording progress is unknown or unsupported. Growing
     * playback requires an explicit [RecordingPlaybackStart.START_OVER], one stable `.ts` file,
     * and supported progress; saved server progress is never reinterpreted as a growing seek.
     */
    public suspend fun setRecordingTarget(
        binding: PlaybackBinding.Recording,
        start: RecordingPlaybackStart = RecordingPlaybackStart.RESUME,
    ): PlaybackTargetResult = setRecordingTarget(BoundCoordinatorRecordingTarget(binding), start)

    internal suspend fun setRecordingTarget(
        target: CoordinatorRecordingTarget,
        start: RecordingPlaybackStart = RecordingPlaybackStart.RESUME,
    ): PlaybackTargetResult {
        val reply = CompletableDeferred<PlaybackTargetResult>()
        val command = CoordinatorCommand.Recording(
            target = target,
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

    private fun publishLiveDiagnostics(
        token: PlaybackTargetToken,
        diagnostics: LiveSubscriptionDiagnostics?,
    ) {
        if (activeTimeshiftToken.get() === token) mutableLiveDiagnostics.value = diagnostics
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
            mutableLiveDiagnostics.value = null
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

/** Creates a narrow playback coordinator over an application-owned [player]. */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendPlaybackCoordinator(
    player: ExoPlayer,
    progressPolicy: DvrProgressPolicy = DvrProgressPolicy(),
    recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit = {},
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): TvheadendPlaybackCoordinator {
    val events = PlaybackPlayerEventAccumulator()
    return TvheadendPlaybackCoordinator(
        player = Media3PlaybackCoordinatorPlayer(
            access = ExoPlayerCoordinatorPlaybackAccess(
                player = player,
                recoveryPolicy = recoveryPolicy,
                onUnsupportedStream = onUnsupportedStream,
            ),
            events = events,
            admitRecording = ::admitRecordingTarget,
        ),
        playerEvents = events,
        progressPolicy = progressPolicy,
        onRecoveryRequired = onRecoveryRequired,
        timeSource = SystemPlaybackCoordinatorTimeSource,
    )
}

internal fun admitRecordingTarget(
    target: CoordinatorRecordingTarget,
    start: RecordingPlaybackStart,
): RecordingAdmission {
    return when (val initial = target.admission) {
        is CoordinatorRecordingAdmission.Completed -> RecordingAdmission.Completed(
            resumePosition = initial.resumePosition.takeIf {
                start == RecordingPlaybackStart.RESUME &&
                    initial.progressCapability == RecordingProgressCapability.SUPPORTED
            },
            progressReportingSupported =
                initial.progressCapability == RecordingProgressCapability.SUPPORTED,
        )
        is CoordinatorRecordingAdmission.GrowingStartOverOnly -> admitGrowingRecording(
            target = target,
            initial = initial,
            start = start,
        )
        CoordinatorRecordingAdmission.GrowingDeferred -> RecordingAdmission.GrowingRecordingDeferred
        CoordinatorRecordingAdmission.TargetUnavailable -> RecordingAdmission.TargetUnavailable
        CoordinatorRecordingAdmission.ObservationExpired -> RecordingAdmission.NotReady
    }
}

private fun admitGrowingRecording(
    target: CoordinatorRecordingTarget,
    initial: CoordinatorRecordingAdmission.GrowingStartOverOnly,
    start: RecordingPlaybackStart,
): RecordingAdmission {
    when (initial.progressCapability) {
        RecordingProgressCapability.UNKNOWN -> return RecordingAdmission.NotReady
        RecordingProgressCapability.UNSUPPORTED -> return RecordingAdmission.ProgressUnsupported
        RecordingProgressCapability.SUPPORTED -> Unit
    }
    if (start == RecordingPlaybackStart.RESUME) {
        return RecordingAdmission.GrowingResumeUnsupported
    }
    val lease = when (val binding = target.bindGrowingRecording()) {
        is RecordingFileResult.Ok -> binding.value
        is RecordingFileResult.Failed -> return when (binding.failure) {
            RecordingFileFailure.CONNECTION_CHANGED -> RecordingAdmission.NotReady
            RecordingFileFailure.ACCESS_DENIED,
            RecordingFileFailure.FILE_UNAVAILABLE,
            RecordingFileFailure.CONNECTION_LIMIT,
            RecordingFileFailure.TIMEOUT,
            RecordingFileFailure.NOT_SUPPORTED,
            -> RecordingAdmission.TargetUnavailable
        }
    }
    if (!lease.isCurrent) return RecordingAdmission.TargetUnavailable
    return when (val current = target.admission) {
        is CoordinatorRecordingAdmission.Completed -> if (
            current.progressCapability == RecordingProgressCapability.SUPPORTED
        ) {
            RecordingAdmission.Growing(lease, progressReportingSupported = true)
        } else {
            RecordingAdmission.NotReady
        }
        is CoordinatorRecordingAdmission.GrowingStartOverOnly -> when (current.progressCapability) {
            RecordingProgressCapability.UNKNOWN -> RecordingAdmission.NotReady
            RecordingProgressCapability.UNSUPPORTED -> RecordingAdmission.ProgressUnsupported
            RecordingProgressCapability.SUPPORTED ->
                RecordingAdmission.Growing(lease, progressReportingSupported = true)
        }
        CoordinatorRecordingAdmission.GrowingDeferred -> RecordingAdmission.GrowingRecordingDeferred
        CoordinatorRecordingAdmission.TargetUnavailable -> RecordingAdmission.TargetUnavailable
        CoordinatorRecordingAdmission.ObservationExpired -> RecordingAdmission.NotReady
    }
}

private class CoordinatorActor(
    private val player: PlaybackCoordinatorPlayer,
    private val playerEvents: PlaybackPlayerEventAccumulator,
    private val commands: Channel<CoordinatorCommand>,
    private val progressPolicy: DvrProgressPolicy,
    private val onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    private val timeSource: PlaybackCoordinatorTimeSource,
    private val onShutdownClaimed: () -> Unit,
    private val publishTimeshiftState: (PlaybackTargetToken, LiveTimeshiftState) -> Unit,
    private val publishSubscriptionIssue: (PlaybackTargetToken, SubscriptionIssue?) -> Unit,
    private val publishLiveDiagnostics: (PlaybackTargetToken, LiveSubscriptionDiagnostics?) -> Unit,
    private val activateTimeshift: (PlaybackTargetToken, LiveTimeshiftControlBridge) -> Unit,
    private val deactivateTimeshift: (PlaybackTargetToken) -> Unit,
) {
    private val mailbox = PlaybackProgressMailbox()
    private val ticks = Channel<PlaybackTargetToken>(Channel.CONFLATED)
    private var activeTarget: ActorTarget? = null
    private var ticker: Job? = null

    suspend fun run(): Unit = coroutineScope {
        val reportWorker = launch { runReportWorker() }
        var processing: CoordinatorCommand? = null
        try {
            var shuttingDown = false
            while (!shuttingDown) {
                select<Unit> {
                    commands.onReceiveCatching { received ->
                        val command = received.getOrNull() ?: return@onReceiveCatching
                        if (command.ticket.isCancelled()) return@onReceiveCatching
                        processing = command
                        shuttingDown = processCommand(command, reportWorker, this@coroutineScope)
                        processing = null
                    }
                    playerEvents.signal.onReceive {
                        drainPlayerEvents()
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
            activeTarget?.token?.retire()
            (activeTarget as? ActorTarget.Live)?.let { target ->
                target.timeshiftControls.retire()
                deactivateTimeshift(target.token)
            }
            ticker?.cancel()
            ticker = null
            (activeTarget as? ActorTarget.Recording)?.reportEpoch?.invalidate()
            mailbox.discard()
            reportWorker.cancelAndJoin()
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
                publishDiagnostics = { diagnostics -> publishLiveDiagnostics(token, diagnostics) },
            )
            val result = player.installLive(
                ticket = command.ticket,
                token = token,
                target = command.target,
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
                target = command.target,
                start = command.start,
            )
            applyRetirement(result.retiredTarget, result.retiredRecording)
            if (result.status == PlaybackPlayerInstallStatus.STARTED) {
                val admission = checkNotNull(result.installedRecording)
                val target = ActorTarget.Recording(
                    token = token,
                    target = command.target,
                    admission = admission,
                )
                activeTarget = target
                if (admission.progressReportingSupported) startReporting(target, scope)
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
                    event.paused && !target.terminalized && refreshRecordingTarget(target) ->
                        target.tracker?.let { tracker ->
                            target.reportEpoch?.takeIf { epoch -> epoch.isValid() }?.let { epoch ->
                                mailbox.offer(
                                    PendingPlaybackProgress(
                                        epoch = epoch,
                                        target = target.target,
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
        if (!refreshRecordingTarget(target)) return
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val snapshot = player.snapshot(token) ?: return
        tracker.onElapsed(timeSource.now(), snapshot.position)?.let { progress ->
            mailbox.offer(
                PendingPlaybackProgress(
                    epoch = epoch,
                    target = target.target,
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
        if (!refreshRecordingTarget(target)) return
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val terminalState = if (
            target.growingLease != null &&
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
                target = target.target,
                growingLease = target.growingLease,
                progress = tracker.onTerminal(
                    position = snapshot.position,
                    duration = snapshot.duration.takeIf { target.growingLease == null },
                    state = terminalState,
                    exit = exit,
                ),
                terminal = true,
            ),
        )
    }

    private suspend fun startReporting(target: ActorTarget.Recording, scope: CoroutineScope) {
        if (!refreshRecordingTarget(target)) return
        val tracker = progressPolicy.tracker()
        target.tracker = tracker
        target.reportEpoch = PlaybackReportEpoch { reportableEntryState(target) != null }
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

    private fun refreshRecordingTarget(target: ActorTarget.Recording): Boolean {
        if (target.reportEpoch?.isValid() == false) return invalidateRecordingTarget(target)
        val state = reportableEntryState(target) ?: return invalidateRecordingTarget(target)
        target.entryState = state
        return true
    }

    private fun reportableEntryState(target: ActorTarget.Recording): DvrEntryState? {
        if (!target.progressReportingSupported || target.growingLease?.isCurrent == false) return null
        return when (val admission = target.target.admission) {
            is CoordinatorRecordingAdmission.Completed ->
                DvrEntryState.COMPLETED.takeIf {
                    admission.progressCapability == RecordingProgressCapability.SUPPORTED
                }
            is CoordinatorRecordingAdmission.GrowingStartOverOnly ->
                DvrEntryState.RECORDING.takeIf {
                    target.growingLease != null &&
                        admission.progressCapability == RecordingProgressCapability.SUPPORTED
                }
            CoordinatorRecordingAdmission.GrowingDeferred,
            CoordinatorRecordingAdmission.TargetUnavailable,
            CoordinatorRecordingAdmission.ObservationExpired,
            -> null
        }
    }

    private fun invalidateRecordingTarget(target: ActorTarget.Recording): Boolean {
        target.reportEpoch?.invalidate()
        target.tracker = null
        ticker?.cancel()
        ticker = null
        mailbox.discardInvalid()
        return false
    }

    private suspend fun runReportWorker() {
        while (true) {
            val report = mailbox.next() ?: return
            if (!report.epoch.isValid()) continue
            try {
                report.target.reportProgress(
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
            val target: CoordinatorRecordingTarget,
            admission: RecordingAdmission.Accepted,
        ) : ActorTarget(token) {
            val progressReportingSupported: Boolean = admission.progressReportingSupported
            var entryState: DvrEntryState = when (admission) {
                is RecordingAdmission.Completed -> DvrEntryState.COMPLETED
                is RecordingAdmission.Growing -> DvrEntryState.RECORDING
            }
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
        val target: CoordinatorLiveTarget,
        val options: SubscriptionOptions,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackTargetResult>,
    ) : CoordinatorCommand(ticket)

    data class Recording(
        val target: CoordinatorRecordingTarget,
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
