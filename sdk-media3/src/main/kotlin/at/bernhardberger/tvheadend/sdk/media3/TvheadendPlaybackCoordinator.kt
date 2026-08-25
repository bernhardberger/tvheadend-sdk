@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrResumeOffer
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Whether completed-recording playback starts at zero or uses the current server resume position. */
public enum class RecordingPlaybackStart {
    START_OVER,
    RESUME,
}

/** Typed outcome of installing a live or completed-recording playback target. */
public enum class PlaybackTargetResult {
    STARTED,
    NOT_RUNNING,
    SHUT_DOWN,
    NOT_READY,
    RECORDING_PROGRESS_UNSUPPORTED,
    TARGET_UNAVAILABLE,
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
    public suspend fun setLiveTarget(channelId: ChannelId): PlaybackTargetResult {
        val reply = CompletableDeferred<PlaybackTargetResult>()
        val command = CoordinatorCommand.Live(
            channelId = SubscriptionChannelId(channelId.value),
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.targetUnavailableResult() }
    }

    /**
     * Replaces the current target with a current, completed recording.
     *
     * Unknown and unsupported recording-progress capability fail before source creation. Growing
     * recordings are explicitly deferred to Phase 7.
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
    val sessionState: StateFlow<SessionState>
    val progressCapability: StateFlow<RecordingProgressCapability>

    fun admitRecording(
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): CompletedRecordingAdmission

    suspend fun reportProgress(recordingId: DvrEntryId, progress: DvrPlaybackProgress)
}

private class SessionPlaybackCoordinatorEnvironment(
    private val session: TvheadendSession,
    private val progressPolicy: DvrProgressPolicy,
) : PlaybackCoordinatorEnvironment {
    override val sessionState: StateFlow<SessionState> = session.state
    override val progressCapability: StateFlow<RecordingProgressCapability> =
        session.recordingProgressCapability

    override fun admitRecording(
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): CompletedRecordingAdmission {
        if (session.state.value !is SessionState.Ready) return CompletedRecordingAdmission.NotReady
        when (session.recordingProgressCapability.value) {
            RecordingProgressCapability.UNKNOWN -> return CompletedRecordingAdmission.NotReady
            RecordingProgressCapability.UNSUPPORTED ->
                return CompletedRecordingAdmission.ProgressUnsupported
            RecordingProgressCapability.SUPPORTED -> Unit
        }
        val current = session.dvrRepository.state.value as? DvrRepositoryState.Current
            ?: return CompletedRecordingAdmission.NotReady
        val entry = current.snapshot.entries.firstOrNull { candidate ->
            candidate.id.value == recordingId.value
        } ?: return CompletedRecordingAdmission.TargetUnavailable
        return when (entry.state) {
            DvrEntryState.RECORDING -> CompletedRecordingAdmission.GrowingRecordingDeferred
            DvrEntryState.COMPLETED -> CompletedRecordingAdmission.Accepted(
                state = DvrEntryState.COMPLETED,
                resumePosition = when (start) {
                    RecordingPlaybackStart.START_OVER -> null
                    RecordingPlaybackStart.RESUME -> when (val offer = progressPolicy.resumeOffer(entry)) {
                        is DvrResumeOffer.Resume -> offer.position
                        DvrResumeOffer.StartOver -> null
                    }
                },
            )
            else -> CompletedRecordingAdmission.TargetUnavailable
        }
    }

    override suspend fun reportProgress(recordingId: DvrEntryId, progress: DvrPlaybackProgress) {
        session.dvrRepository.reportProgress(recordingId, progress)
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
            combine(environment.sessionState, environment.progressCapability) { state, capability ->
                state is SessionState.Ready && capability == RecordingProgressCapability.SUPPORTED
            }.collect { valid ->
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
            val result = player.installLive(
                ticket = command.ticket,
                token = token,
                channelId = command.channelId,
            )
            applyRetirement(result.retiredTarget, result.retiredRecording)
            if (result.status == PlaybackPlayerInstallStatus.STARTED) {
                ticker?.cancel()
                activeTarget = ActorTarget.Live(token)
            } else {
                token.retire()
            }
            if (result.status != PlaybackPlayerInstallStatus.CANCELLED) {
                command.reply.complete(result.status.toPublicTargetResult())
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
                val state = checkNotNull(result.installedRecordingState)
                val target = ActorTarget.Recording(
                    token = token,
                    recordingId = DvrEntryId(command.recordingId.value),
                    entryState = state,
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

    private suspend fun applyRetirement(
        retiredTarget: Boolean,
        retiredRecording: RetiredRecordingTarget?,
    ) {
        if (!retiredTarget) return
        val old = activeTarget
        if (old is ActorTarget.Recording && retiredRecording?.token === old.token) {
            terminalize(old, retiredRecording.snapshot, retiredRecording.exit)
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
                    )
                    event.paused && !target.terminalized -> target.tracker?.let { tracker ->
                        target.reportEpoch?.takeIf { epoch -> epoch.isValid() }?.let { epoch ->
                            mailbox.offer(
                                PendingPlaybackProgress(
                                    epoch = epoch,
                                    recordingId = target.recordingId,
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
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val snapshot = player.snapshot(token) ?: return
        tracker.onElapsed(timeSource.now(), snapshot.position)?.let { progress ->
            mailbox.offer(
                PendingPlaybackProgress(
                    epoch = epoch,
                    recordingId = target.recordingId,
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
    ) {
        if (target.terminalized) return
        target.terminalized = true
        ticker?.cancel()
        ticker = null
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        mailbox.offer(
            PendingPlaybackProgress(
                epoch = epoch,
                recordingId = target.recordingId,
                progress = tracker.onTerminal(
                    position = snapshot.position,
                    duration = snapshot.duration,
                    state = target.entryState,
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
        val tracker = progressPolicy.tracker()
        target.tracker = tracker
        target.reportEpoch = PlaybackReportEpoch(gate)
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

    private fun currentReportGateIsValid(): Boolean =
        environment.sessionState.value is SessionState.Ready &&
            environment.progressCapability.value == RecordingProgressCapability.SUPPORTED

    private suspend fun runReportWorker() {
        while (true) {
            val report = mailbox.next() ?: return
            if (!report.epoch.isValid() || !currentReportGateIsValid()) continue
            try {
                environment.reportProgress(report.recordingId, report.progress)
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
        data class Live(override val token: PlaybackTargetToken) : ActorTarget(token)

        data class Recording(
            override val token: PlaybackTargetToken,
            val recordingId: DvrEntryId,
            val entryState: DvrEntryState,
            var tracker: at.bernhardberger.tvheadend.sdk.core.DvrProgressTracker? = null,
            var reportEpoch: PlaybackReportEpoch? = null,
            var terminalized: Boolean = false,
        ) : ActorTarget(token)
    }
}

private sealed class CoordinatorCommand(
    open val ticket: PlayerOperationTicket,
) {
    data class Live(
        val channelId: SubscriptionChannelId,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackTargetResult>,
    ) : CoordinatorCommand(ticket)

    data class Recording(
        val recordingId: RecordingId,
        val start: RecordingPlaybackStart,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<PlaybackTargetResult>,
    ) : CoordinatorCommand(ticket)

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

private fun PlaybackPlayerInstallStatus.toPublicTargetResult(): PlaybackTargetResult = when (this) {
    PlaybackPlayerInstallStatus.STARTED -> PlaybackTargetResult.STARTED
    PlaybackPlayerInstallStatus.NOT_READY -> PlaybackTargetResult.NOT_READY
    PlaybackPlayerInstallStatus.RECORDING_PROGRESS_UNSUPPORTED ->
        PlaybackTargetResult.RECORDING_PROGRESS_UNSUPPORTED
    PlaybackPlayerInstallStatus.TARGET_UNAVAILABLE -> PlaybackTargetResult.TARGET_UNAVAILABLE
    PlaybackPlayerInstallStatus.GROWING_RECORDING_DEFERRED ->
        PlaybackTargetResult.GROWING_RECORDING_DEFERRED
    PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE -> PlaybackTargetResult.PLAYER_UNAVAILABLE
    PlaybackPlayerInstallStatus.CANCELLED -> error("Cancelled player operations have no result")
}
