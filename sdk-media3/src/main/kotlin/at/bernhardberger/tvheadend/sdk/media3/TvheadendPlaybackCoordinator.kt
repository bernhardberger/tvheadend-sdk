@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionPriority
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import androidx.media3.common.Player
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
    /**
     * Resumes near a positive saved server position; without one, playback starts over.
     *
     * Completed recordings seek once their seek map is known and start over when progress is
     * unknown or unsupported. Active single-file `.ts` recordings with supported progress seek once
     * the growing timeline becomes seekable; the admission gates for active recordings are the same
     * as for [START_OVER]. See [TvheadendPlaybackCoordinator.setRecordingTarget].
     */
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

/** Closed disposition of target installation without exhaustively defining its exact outcome. */
public enum class PlaybackTargetDisposition {
    STARTED,
    /** Installed, but application replacement ended Player ownership during installation. */
    DEPARTED,
    NOT_STARTED,
}

/**
 * Typed outcome of installing a live or completed-recording playback target.
 *
 * Exact values are SDK-owned singletons rather than an exhaustive enum. Applications may compare
 * a known value or use the stable disposition, categories, and predicates, but must retain a
 * fallback for future exact outcomes. An empty category set means no stable broad classification
 * is available. No current target result is configuration/access-related or uncertain; those
 * shared category predicates remain false unless a future exact result carries such a category.
 */
public class PlaybackTargetResult private constructor(
    private val label: String,
    public val disposition: PlaybackTargetDisposition,
    public val categories: Set<PlaybackOutcomeCategory>,
) {
    public val isStarted: Boolean
        get() = disposition == PlaybackTargetDisposition.STARTED

    public val isTransient: Boolean
        get() = PlaybackOutcomeCategory.TRANSIENT in categories

    public val isTerminal: Boolean
        get() = PlaybackOutcomeCategory.TERMINAL in categories

    public val isUnsupported: Boolean
        get() = PlaybackOutcomeCategory.UNSUPPORTED in categories

    public val isConfigurationOrAccessRelated: Boolean
        get() = PlaybackOutcomeCategory.CONFIGURATION_OR_ACCESS in categories

    public val isOutcomeUncertain: Boolean
        get() = PlaybackOutcomeCategory.UNCERTAIN in categories

    override fun toString(): String = label

    public companion object {
        /** Installation succeeded and retained Player item ownership through installation. */
        @JvmField
        public val STARTED: PlaybackTargetResult = PlaybackTargetResult(
            "STARTED",
            PlaybackTargetDisposition.STARTED,
            emptySet(),
        )

        /**
         * Installation occurred, but the application left the item during installation. The
         * coordinator does not prepare the application's item or reacquire ownership. The target
         * remains installed until stop, replacement or shutdown; explicitly install again to play it.
         */
        @JvmField
        public val DEPARTED: PlaybackTargetResult = PlaybackTargetResult(
            "DEPARTED",
            PlaybackTargetDisposition.DEPARTED,
            emptySet(),
        )

        @JvmField
        public val NOT_RUNNING: PlaybackTargetResult = transient("NOT_RUNNING")

        @JvmField
        public val SHUT_DOWN: PlaybackTargetResult = categorized(
            "SHUT_DOWN",
            PlaybackOutcomeCategory.TERMINAL,
        )

        @JvmField
        public val NOT_READY: PlaybackTargetResult = transient("NOT_READY")

        @JvmField
        public val RECORDING_PROGRESS_UNSUPPORTED: PlaybackTargetResult = categorized(
            "RECORDING_PROGRESS_UNSUPPORTED",
            PlaybackOutcomeCategory.UNSUPPORTED,
        )

        @JvmField
        public val TARGET_UNAVAILABLE: PlaybackTargetResult = result("TARGET_UNAVAILABLE")

        /**
         * Formerly returned when an active recording was requested with server-progress resume.
         *
         * Active single-file `.ts` recordings now resume, so the coordinator no longer produces
         * this result. It remains for source and binary compatibility of exhaustive consumers.
         */
        @JvmField
        public val GROWING_RECORDING_RESUME_UNSUPPORTED: PlaybackTargetResult = categorized(
            "GROWING_RECORDING_RESUME_UNSUPPORTED",
            PlaybackOutcomeCategory.UNSUPPORTED,
        )

        /** The active recording is outside the supported single-file pass-through MPEG-TS path. */
        @JvmField
        public val GROWING_RECORDING_DEFERRED: PlaybackTargetResult = categorized(
            "GROWING_RECORDING_DEFERRED",
            PlaybackOutcomeCategory.TRANSIENT,
            PlaybackOutcomeCategory.UNSUPPORTED,
        )

        @JvmField
        public val PLAYER_UNAVAILABLE: PlaybackTargetResult = transient("PLAYER_UNAVAILABLE")

        private fun result(label: String): PlaybackTargetResult = PlaybackTargetResult(
            label,
            PlaybackTargetDisposition.NOT_STARTED,
            emptySet(),
        )

        private fun transient(label: String): PlaybackTargetResult = categorized(
            label,
            PlaybackOutcomeCategory.TRANSIENT,
        )

        private fun categorized(
            label: String,
            vararg categories: PlaybackOutcomeCategory,
        ): PlaybackTargetResult = PlaybackTargetResult(
            label,
            PlaybackTargetDisposition.NOT_STARTED,
            Collections.unmodifiableSet(categories.toSet()),
        )
    }
}

/** Typed result of binding and installing one exact current-session live target. */
public sealed interface LivePlaybackTargetResult {
    /** Binding succeeded; [result] is the coordinator's exact installation outcome. */
    public class Bound internal constructor(
        public val result: PlaybackTargetResult,
    ) : LivePlaybackTargetResult

    /** The supplied current-session observation was no longer authoritative. */
    public data object ObservationExpired : LivePlaybackTargetResult

    /** The channel was unavailable in the supplied current-session observation. */
    public data object TargetUnavailable : LivePlaybackTargetResult
}

/** Conflated, target-fenced live state for consumers that need one coherent observation. */
public sealed interface LivePlaybackObservation {
    /** No live target is active; a recording target may still be active. */
    public data object NoTarget : LivePlaybackObservation

    /**
     * The installed live target no longer owns the Player item. Ownership cannot be reacquired;
     * stop, replacement or shutdown retires the target. Install explicitly to play it again.
     * Later subscription observations cannot restore [Active].
     * The separate [TvheadendPlaybackCoordinator.timeshiftState],
     * [TvheadendPlaybackCoordinator.subscriptionIssue] and [TvheadendPlaybackCoordinator.liveDiagnostics]
     * flows, and timeshift commands, keep tracking this still-installed target until retirement.
     */
    public data object Departed : LivePlaybackObservation

    /**
     * Latest coherent state for the active live target.
     *
     * [serverStopped] is true from a server subscription stop, with or without a
     * [subscriptionIssue], until the same target restarts, ends or is retired. While it is true no
     * media is delivered for this target. A server stop without an issue is therefore observable
     * here even though [subscriptionIssue] stays null.
     */
    @ConsistentCopyVisibility
    public data class Active internal constructor(
        public val timeshiftState: LiveTimeshiftState,
        public val subscriptionIssue: SubscriptionIssue?,
        public val diagnostics: LiveSubscriptionDiagnostics?,
        public val serverStopped: Boolean = false,
    ) : LivePlaybackObservation
}

/** Typed outcome of retiring the coordinator's current target. */
public sealed interface PlaybackStopResult {
    /**
     * The installed target was retired and its item removed from the playlist. The player was
     * stopped because the coordinator's item was current, including when the application returned
     * to it after departure. This does not restore ownership for reporting. Application-added
     * playlist items are retained.
     *
     * [finalSubscriptionIssue] is the last [SubscriptionIssue] published for the retired live target,
     * so a consumer that stops a failing target can report why without reading
     * [TvheadendPlaybackCoordinator.subscriptionIssue] first. It is null for a recording target or
     * when no issue was current for the live target.
     */
    public data class Stopped(
        public val finalSubscriptionIssue: SubscriptionIssue?,
    ) : PlaybackStopResult

    /**
     * The target was retired without stopping the Player: no coordinator item was current, so
     * application playback was left alone and only any lingering coordinator item was removed.
     * If the application returned to that item and retirement stopped the Player, the result is
     * [Stopped] instead. [finalSubscriptionIssue] has the same meaning as in [Stopped].
     */
    public data class RetiredAfterDeparture(
        public val finalSubscriptionIssue: SubscriptionIssue?,
    ) : PlaybackStopResult

    /** No target was installed. */
    public data object AlreadyStopped : PlaybackStopResult

    /** The coordinator has not been launched. */
    public data object NotRunning : PlaybackStopResult

    /** The coordinator was shut down. */
    public data object ShutDown : PlaybackStopResult

    /**
     * The application-owned player was unavailable.
     *
     * The installed target may still have been retired before or while player cleanup failed. In
     * that case [finalSubscriptionIssue] is the last [SubscriptionIssue] published for the retired
     * live target, as for [Stopped]. It is null when no live target was retired, for a recording
     * target, or when no issue was current for the retired live target.
     */
    public data class PlayerUnavailable(
        public val finalSubscriptionIssue: SubscriptionIssue?,
    ) : PlaybackStopResult
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
 * One launched, non-restartable playback-coordinator lifetime.
 *
 * [shutdown] combines the coordinator's typed terminal request with actor completion. [join] only
 * observes completion and never cancels the coordinator when its caller is cancelled.
 */
public sealed interface PlaybackCoordinatorLifetime {
    /** Shuts down the coordinator and waits for all of its internal cleanup to complete. */
    public suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult

    /** Java-friendly millisecond form of [shutdown]. */
    public suspend fun shutdownMillis(drainTimeoutMillis: Long): PlaybackShutdownResult

    /** Waits for completion and rethrows the retained actor failure on every failed join. */
    public suspend fun join(): Unit
}

private class DefaultPlaybackCoordinatorLifetime(
    private val coordinator: TvheadendPlaybackCoordinator,
    private val actor: Deferred<Unit>,
) : PlaybackCoordinatorLifetime {
    override suspend fun shutdown(drainTimeout: Duration): PlaybackShutdownResult {
        return try {
            val result = coordinator.shutdown(drainTimeout)
            actor.join()
            result
        } catch (cancellation: CancellationException) {
            actor.cancel(cancellation)
            withContext(NonCancellable) { actor.join() }
            throw cancellation
        }
    }

    override suspend fun shutdownMillis(drainTimeoutMillis: Long): PlaybackShutdownResult =
        shutdown(drainTimeoutMillis.milliseconds)

    override suspend fun join(): Unit = actor.await()
}

/**
 * Serializes TVHeadend source changes on an application-owned [ExoPlayer].
 *
 * The application retains ownership of the player and may use [withLifetime] for a structured
 * lifetime or [launchIn] when its owner cannot be expressed as one suspending block. This
 * coordinator never constructs or releases the player, never changes autoplay, and owns no
 * MediaSession, service, audio focus, notification, surface, navigation, or presentation policy.
 * Target, stop, and shutdown intents may be called from any coroutine. All player work is moved to
 * the player's application looper, and cancellation before that work is claimed prevents player
 * mutation.
 *
 * Player state belongs to a target only while the player's current item is the one this
 * coordinator installed for it. Once the application replaces or clears that item, the coordinator
 * reads no further position, end, error or recovery state from the player for the target. Pending
 * nonterminal recording progress and recovery are discarded; an already-started upload and a
 * terminal event reached before replacement may still finish. Automatic playlist advance away from
 * a recording also counts as a natural end, but seeking away, playlist changes and repeat do not.
 * The next target, [stop] or [shutdown] retires it and removes any lingering target item without
 * stopping or removing application items. Without an earlier terminal, retirement reports the last
 * observed owned recording position once as orderly progress, never watched; without an owned
 * snapshot there is no closing report.
 *
 * If an application listener replaces or clears the item during its installation transition,
 * installation returns [PlaybackTargetResult.DEPARTED] with the target already departed. The
 * coordinator neither prepares the application's item nor restores the previous target over it.
 * Recording closing reports retain the admitted resume floor even when no resume helper was started.
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
    private val liveObservationLock = Any()
    private var activeTimeshiftToken: PlaybackTargetToken? = null
    private val mutableTimeshiftState = MutableStateFlow<LiveTimeshiftState>(
        LiveTimeshiftState.Unavailable,
    )
    private val mutableSubscriptionIssue = MutableStateFlow<SubscriptionIssue?>(null)
    private val mutableLiveDiagnostics = MutableStateFlow<LiveSubscriptionDiagnostics?>(null)
    private val mutableLivePlaybackObservation = MutableStateFlow<LivePlaybackObservation>(
        LivePlaybackObservation.NoTarget,
    )

    /** Current app-safe timeshift state for the active live target. */
    public val timeshiftState: StateFlow<LiveTimeshiftState> = mutableTimeshiftState.asStateFlow()

    /** Canonical issue reported for the current live target, or null when none is current. */
    public val subscriptionIssue: StateFlow<SubscriptionIssue?> =
        mutableSubscriptionIssue.asStateFlow()

    /** App-safe observations for the current live target, or null when none are available. */
    public val liveDiagnostics: StateFlow<LiveSubscriptionDiagnostics?> =
        mutableLiveDiagnostics.asStateFlow()

    /** Latest coherent observation for the active live target, or [LivePlaybackObservation.NoTarget]. */
    public val livePlaybackObservation: StateFlow<LivePlaybackObservation> =
        mutableLivePlaybackObservation.asStateFlow()

    /**
     * Launches this one-shot coordinator immediately as a child of [scope].
     *
     * Prefer [withLifetime] when the full consumer workflow fits in one suspending block.
     */
    public fun launchIn(scope: CoroutineScope): PlaybackCoordinatorLifetime {
        claimRun()
        return DefaultPlaybackCoordinatorLifetime(
            coordinator = this,
            actor = scope.async(start = CoroutineStart.UNDISPATCHED) { runClaimed() },
        )
    }

    /**
     * Runs [block] with a launched coordinator and a scope for lifetime-bound collectors.
     *
     * Normal block completion cancels its collector children, shuts down the coordinator, and
     * returns typed shutdown evidence. Block failure and caller cancellation are rethrown only
     * after the same terminal cleanup completes.
     */
    public suspend fun withLifetime(
        drainTimeout: Duration,
        block: suspend CoroutineScope.(TvheadendPlaybackCoordinator) -> Unit,
    ): PlaybackShutdownResult {
        validatePlaybackShutdownDrainTimeout(drainTimeout)
        return coroutineScope {
            val lifetime = launchIn(this)
            val collectorJob = Job(coroutineContext[Job])
            val collectorScope = CoroutineScope(coroutineContext + collectorJob)
            try {
                collectorScope.block(this@TvheadendPlaybackCoordinator)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    collectorJob.cancelAndJoin()
                    try {
                        lifetime.shutdown(drainTimeout)
                    } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
            withContext(NonCancellable) { collectorJob.cancelAndJoin() }
            lifetime.shutdown(drainTimeout)
        }
    }

    /** Runs the coordinator until [shutdown] completes or the caller cancels this boundary. */
    public suspend fun run() {
        claimRun()
        runClaimed()
    }

    private fun claimRun() {
        check(lifecycle.compareAndSet(CoordinatorLifecycle.NEW, CoordinatorLifecycle.RUNNING)) {
            "Playback coordinator run is one-shot"
        }
    }

    private suspend fun runClaimed() {
        try {
            CoordinatorActor(
                player = player,
                playerEvents = playerEvents,
                commands = commands,
                progressPolicy = progressPolicy,
                onRecoveryRequired = onRecoveryRequired,
                timeSource = timeSource,
                onShutdownClaimed = { lifecycle.set(CoordinatorLifecycle.SHUTTING_DOWN) },
                publishLiveObservation = ::publishLiveObservation,
                activateTimeshift = ::activateTimeshift,
                deactivateTimeshift = ::deactivateTimeshift,
            ).run()
        } finally {
            withContext(NonCancellable) {
                try {
                    player.abandon()
                } finally {
                    playerEvents.discard()
                    lifecycle.set(CoordinatorLifecycle.STOPPED)
                    commands.close()
                    rejectQueuedCommands()
                }
            }
        }
    }

    /** Replaces the current live target with the exact observation-bound [binding]. */
    public suspend fun setLiveTarget(
        binding: PlaybackBinding.Live,
        options: LivePlaybackOptions = LivePlaybackOptions(),
    ): PlaybackTargetResult = setLiveTarget(BoundCoordinatorLiveTarget(binding), options)

    /**
     * Binds and installs one live target from the caller's exact [currentSession] proof.
     *
     * Binding is lazy and attempted once. This operation never reacquires a generation, retries,
     * or retains [channelId] for reconnect; those choices remain application policy.
     */
    @JvmName("setLiveTarget")
    public suspend fun setLiveTarget(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        options: LivePlaybackOptions = LivePlaybackOptions(),
    ): LivePlaybackTargetResult {
        currentCoroutineContext().ensureActive()
        val binding = session.bindLivePlayback(currentSession, channelId)
        currentCoroutineContext().ensureActive()
        return when (binding) {
            is PlaybackBindingResult.Bound -> LivePlaybackTargetResult.Bound(
                setLiveTarget(binding.binding, options),
            )
            PlaybackBindingResult.ObservationExpired -> LivePlaybackTargetResult.ObservationExpired
            PlaybackBindingResult.TargetUnavailable -> LivePlaybackTargetResult.TargetUnavailable
        }
    }

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
     * Completed playback starts over when recording progress is unknown or unsupported; with
     * [RecordingPlaybackStart.RESUME] and a positive saved position it seeks there once the seek
     * map is known.
     *
     * Growing playback, for either start mode, returns [PlaybackTargetResult.NOT_READY] while
     * progress support is unknown, [PlaybackTargetResult.RECORDING_PROGRESS_UNSUPPORTED] when it is
     * unsupported, [PlaybackTargetResult.TARGET_UNAVAILABLE] without exactly one usable recording file, and
     * [PlaybackTargetResult.GROWING_RECORDING_DEFERRED] for a file that is not `.ts`. For an
     * admitted active `.ts` recording, [RecordingPlaybackStart.START_OVER] or a missing or zero
     * saved position plays from the beginning. [RecordingPlaybackStart.RESUME] with a positive
     * saved position starts at 0:00 and seeks once as soon as the growing timeline is seekable,
     * which takes a few seconds while the file extent is probed; a saved position within three
     * seconds of, or past, the probed end resumes three seconds before it. If the timeline does not
     * become seekable within 20 seconds, the resume is abandoned and playback simply continues.
     *
     * A viewer seek, target replacement or stop cancels a pending resume. While its progress hold
     * remains active, progress reporting does not write an earlier position over the saved one. The
     * hold ends when the resume seek is issued or the resume is cancelled or abandoned; for a
     * completed recording that stays unseekable it ends after at most 60 seconds while the seek
     * itself stays pending.
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

    /** Convenience seek with one client-position anchor and SDK-owned clamping. */
    public suspend fun seekTimeshiftBy(offset: Duration): TimeshiftContentSeekResult {
        val sample = timeshiftPlaybackPosition() as? TimeshiftPlaybackPosition.Estimate
            ?: return TimeshiftContentSeekResult.Unavailable
        val selection = sample.timeline?.resolveSelection(sample.target, delta = offset)
            ?: return TimeshiftContentSeekResult.Unavailable
        return seekTimeshift(selection)
    }

    /** Commit a preview, refreshing its bounds and preserving latest-edge intent. */
    public suspend fun seekTimeshift(selection: TimeshiftSeekSelection): TimeshiftContentSeekResult {
        val reply = CompletableDeferred<TimeshiftContentSeekResult>()
        return submit(CoordinatorCommand.ContentSeek(selection.target, PlayerOperationTicket(), reply, selection), reply) {
            TimeshiftContentSeekResult.Unavailable
        }
    }

    /** Seeks the originally selected content, rejecting expired or replaced subscription targets. */
    public suspend fun seekTimeshift(target: TimeshiftContentTarget): TimeshiftContentSeekResult {
        val reply = CompletableDeferred<TimeshiftContentSeekResult>()
        return submit(CoordinatorCommand.ContentSeek(target, PlayerOperationTicket(), reply), reply) {
            TimeshiftContentSeekResult.Unavailable
        }
    }

    /** Samples the borrowed player's position on its looper and maps only observed packet segments. */
    public suspend fun timeshiftPlaybackPosition(): TimeshiftPlaybackPosition {
        val reply = CompletableDeferred<TimeshiftPlaybackPosition>()
        return submit(CoordinatorCommand.ContentPosition(PlayerOperationTicket(), reply), reply) {
            TimeshiftPlaybackPosition.Unavailable
        }
    }

    /**
     * Requests the existing bounded near-live target for the current live subscription.
     *
     * While playback is paused, an accepted request refills the player as [seekTimeshift] does and
     * returns that seek's [TimeshiftContentSeekResult.Completed.command]: a later command that ends
     * the refill early leaves the server's acceptance in place of a transient failure.
     */
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

    /**
     * Sets the server priority of the current live target's subscription when tuners are contended.
     *
     * The priority is sticky for the current live target: subscriptions the SDK opens again for the
     * same target request it, including when this call's immediate change failed. Installing a new
     * target or stopping resets it to [LiveSubscriptionPriority.NORMAL]. Deciding when to yield is
     * application policy.
     *
     * Returns [TimeshiftCommandResult.ACCEPTED] once the server accepted the change, or once it was
     * recorded while no subscription is currently open or the subscription ends meanwhile.
     * [TimeshiftCommandResult.UNAVAILABLE] means no live target is current, as for recordings.
     * Servers or stream profiles that force their own priority accept the request but ignore it.
     *
     * A priority recorded while a subscription is opening is applied best-effort once it opens;
     * a failure there is not reported. After any failed or inconclusive result the server's
     * priority is unknown, so calling again with the same priority re-sends it. Re-asserting the
     * priority is the recovery.
     */
    public suspend fun setLivePriority(priority: LiveSubscriptionPriority): TimeshiftCommandResult {
        val reply = CompletableDeferred<TimeshiftCommandResult>()
        val command = CoordinatorCommand.LivePriority(
            priority = priority,
            ticket = PlayerOperationTicket(),
            reply = reply,
        )
        return submit(command, reply) { state -> state.timeshiftUnavailableResult() }
    }

    /**
     * Retires the current target and removes only its item, stopping the player only while it plays that target.
     *
     * If the application moved to another item, any lingering target item is removed without
     * stopping playback or removing application items. The result is [PlaybackStopResult.RetiredAfterDeparture].
     * If it returned to the target's item, retirement stops the Player and returns [PlaybackStopResult.Stopped],
     * without reclaiming ownership for reporting.
     * If the application replaced or cleared the recording's item, its last observed owned position
     * supplies closing progress without marking it watched, unless a terminal was already captured
     * or reported. No owned snapshot means no report. While ownership has never been lost and its
     * item is still current, an orderly stop can mark it watched according to the configured progress policy.
     */
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
        validatePlaybackShutdownDrainTimeout(drainTimeout)
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

    private fun publishLiveObservation(
        token: PlaybackTargetToken,
        observation: LivePlaybackObservation.Active,
    ) {
        synchronized(liveObservationLock) {
            // Source teardown after retirement must not overwrite the retired target's final state.
            if (activeTimeshiftToken !== token || !token.isActive()) return
            mutableTimeshiftState.value = observation.timeshiftState
            mutableSubscriptionIssue.value = observation.subscriptionIssue
            mutableLiveDiagnostics.value = observation.diagnostics
            mutableLivePlaybackObservation.value = if (token.ownsPlayerItem()) observation
            else LivePlaybackObservation.Departed
        }
    }

    private fun activateTimeshift(
        token: PlaybackTargetToken,
        bridge: LiveTimeshiftControlBridge,
    ) {
        synchronized(liveObservationLock) {
            activeTimeshiftToken = token
            mutableTimeshiftState.value = LiveTimeshiftState.Unavailable
            mutableSubscriptionIssue.value = null
            mutableLiveDiagnostics.value = null
            mutableLivePlaybackObservation.value = if (token.ownsPlayerItem()) LivePlaybackObservation.Active(
                timeshiftState = LiveTimeshiftState.Unavailable,
                subscriptionIssue = null,
                diagnostics = null,
            ) else LivePlaybackObservation.Departed
        }
        bridge.publishCurrent()
    }

    private fun deactivateTimeshift(token: PlaybackTargetToken): SubscriptionIssue? {
        synchronized(liveObservationLock) {
            if (activeTimeshiftToken !== token) return null
            val finalSubscriptionIssue = mutableSubscriptionIssue.value
            activeTimeshiftToken = null
            mutableTimeshiftState.value = LiveTimeshiftState.Unavailable
            mutableSubscriptionIssue.value = null
            mutableLiveDiagnostics.value = null
            mutableLivePlaybackObservation.value = LivePlaybackObservation.NoTarget
            return finalSubscriptionIssue
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
    }
}

private fun validatePlaybackShutdownDrainTimeout(drainTimeout: Duration) {
    require(
        drainTimeout.isFinite() &&
            !drainTimeout.isNegative() &&
            drainTimeout <= 10.seconds,
    ) {
        "Playback shutdown drain timeout must be finite and between zero and ten seconds"
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
    val lease = when (val binding = target.bindGrowingRecording()) {
        is RecordingFileResult.Ok -> binding.value
        is RecordingFileResult.Failed -> return when (binding.failure) {
            RecordingFileFailure.CONNECTION_CHANGED -> RecordingAdmission.NotReady
            RecordingFileFailure.ACCESS_DENIED,
            RecordingFileFailure.FILE_UNAVAILABLE,
            RecordingFileFailure.CONNECTION_LIMIT,
            RecordingFileFailure.TIMEOUT,
            RecordingFileFailure.NOT_SUPPORTED,
            RecordingFileFailure.MALFORMED_RESPONSE,
            -> RecordingAdmission.TargetUnavailable
        }
    }
    if (!lease.isCurrent) return RecordingAdmission.TargetUnavailable
    // The saved position is taken from the admission re-read after binding, so a recording that
    // completed meanwhile still resumes from its latest observed server progress.
    return when (val current = target.admission) {
        is CoordinatorRecordingAdmission.Completed -> if (
            current.progressCapability == RecordingProgressCapability.SUPPORTED
        ) {
            RecordingAdmission.Growing(
                lease = lease,
                progressReportingSupported = true,
                resumePosition = current.resumePosition.takeIf { start == RecordingPlaybackStart.RESUME },
            )
        } else {
            RecordingAdmission.NotReady
        }
        is CoordinatorRecordingAdmission.GrowingStartOverOnly -> when (current.progressCapability) {
            RecordingProgressCapability.UNKNOWN -> RecordingAdmission.NotReady
            RecordingProgressCapability.UNSUPPORTED -> RecordingAdmission.ProgressUnsupported
            RecordingProgressCapability.SUPPORTED -> RecordingAdmission.Growing(
                lease = lease,
                progressReportingSupported = true,
                resumePosition = current.resumePosition.takeIf { start == RecordingPlaybackStart.RESUME },
            )
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
    private val publishLiveObservation: (PlaybackTargetToken, LivePlaybackObservation.Active) -> Unit,
    private val activateTimeshift: (PlaybackTargetToken, LiveTimeshiftControlBridge) -> Unit,
    private val deactivateTimeshift: (PlaybackTargetToken) -> SubscriptionIssue?,
) {
    private val mailbox = PlaybackProgressMailbox()
    private val ticks = Channel<PlaybackTargetToken>(Channel.CONFLATED)
    private val exhaustions = Channel<LiveBufferExhaustion>(Channel.UNLIMITED)
    private var activeTarget: ActorTarget? = null
    private var ticker: Job? = null
    private var pausedSeek: PausedSeekBuffer? = null
    private var owedPause: OwedPause? = null

    suspend fun run(): Unit = coroutineScope {
        val reportWorker = launch { runReportWorker() }
        var processing: CoordinatorCommand? = null
        try {
            var shuttingDown = false
            while (!shuttingDown) {
                select<Unit> {
                    // Registered first: select prefers earlier ready clauses, and a steady command stream
                    // must not delay readiness, the timeout or a pause owed by a full-speed transport.
                    pausedSeek?.let { buffer ->
                        buffer.polls.onReceive { expired -> pollPausedSeek(buffer, expired, this@coroutineScope) }
                    }
                    owedPause?.let { owed ->
                        owed.settled.onReceive { repayPause(owed, this@coroutineScope) }
                    }
                    commands.onReceiveCatching { received ->
                        val command = received.getOrNull() ?: return@onReceiveCatching
                        if (command.ticket.isCancelled()) return@onReceiveCatching
                        processing = command
                        shuttingDown = processAfterPausedSeek(command, reportWorker, this@coroutineScope)
                        processing = null
                    }
                    playerEvents.signal.onReceive {
                        drainPlayerEvents()
                    }
                    exhaustions.onReceive { exhaustion ->
                        endExhaustedLiveTarget(exhaustion)
                    }
                    ticks.onReceive { token ->
                        observeElapsed(token)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            processing?.rejectAfterShutdown()
            endPausedSeek(cancellation)
            throw cancellation
        } catch (failure: Exception) {
            processing?.rejectAfterShutdown()
            endPausedSeek(failure)
            throw failure
        } finally {
            exhaustions.close()
            while (true) {
                val pending = exhaustions.tryReceive().getOrNull() ?: break
                pending.settle(true)
            }
            owedPause?.watcher?.cancel()
            owedPause = null
            activeTarget?.token?.retire()
            (activeTarget as? ActorTarget.Live)?.let { target ->
                deactivateTimeshift(target.token)
                target.timeshiftControls.retire()
            }
            ticker?.cancel()
            ticker = null
            (activeTarget as? ActorTarget.Recording)?.reportEpoch?.invalidate()
            mailbox.discard()
            reportWorker.cancelAndJoin()
        }
    }

    /**
     * Ends pending paused-seek buffering before a command that supersedes it.
     *
     * Transport commands pause the buffering transport first. Target replacement, stop and shutdown
     * are not delayed by that round trip: retirement closes the transport, and only a target that
     * survives the command is paused again afterwards. The superseded seek keeps its acknowledgement.
     */
    private suspend fun processAfterPausedSeek(
        command: CoordinatorCommand,
        reportWorker: Job,
        scope: CoroutineScope,
    ): Boolean {
        val buffer = pausedSeek?.takeIf { command.supersedesPausedSeek }
            ?: return processCommand(command, reportWorker, scope)
        buffer.timer?.cancel()
        if (!command.mayRetireTarget) {
            finishPausedSeek(buffer, TimeshiftCommandResult.UNAVAILABLE, scope, superseded = true)
            return processCommand(command, reportWorker, scope)
        }
        val shuttingDown = processCommand(command, reportWorker, scope)
        finishPausedSeek(buffer, TimeshiftCommandResult.UNAVAILABLE, scope, superseded = true)
        return shuttingDown
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
                publish = {},
                publishIssue = {},
                publishObservation = { observation -> publishLiveObservation(token, observation) },
                onBufferExhausted = { attachment, settle ->
                    exhaustions.trySend(LiveBufferExhaustion(token, attachment, settle)).isSuccess
                },
            )
            val result = player.installLive(
                ticket = command.ticket,
                token = token,
                target = command.target,
                options = command.options,
                timeshiftControls = timeshiftControls,
            )
            applyRetirement(result.retiredTarget, result.retiredRecording)
            if (result.status == PlaybackPlayerInstallStatus.STARTED || result.status == PlaybackPlayerInstallStatus.DEPARTED) {
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
            if (command.ticket.claim()) {
                val target = (activeTarget as? ActorTarget.Live)?.takeIf { it.token.isActive() }
                val attachment = target?.timeshiftControls?.mappingAttachment()
                try {
                    val seek = attachment?.let(::TimeshiftSeekToken)
                    attachment?.bufferingSeek =
                        (attachment?.availableState() as? LiveTimeshiftState.Available)?.playbackPaused == true
                    val result = target?.timeshiftControls?.seek(command.target, seek)?.toPublicTimeshiftResult()
                        ?: TimeshiftCommandResult.UNAVAILABLE
                    val completed = if (target != null && result.isAccepted && seek != null) {
                        startPausedSeek(
                            target, TimeshiftContentSeekResult.Completed(result, null, seek), command, command.reply, scope,
                        ) { command.reply.complete(it.command) }?.command
                    } else {
                        result
                    }
                    if (completed != null) {
                        command.ticket.complete()
                        command.reply.complete(completed)
                    }
                } catch (cancellation: CancellationException) {
                    command.ticket.complete()
                    command.reply.completeExceptionally(cancellation)
                    throw cancellation
                } finally {
                    // Pending buffering keeps recording rendered frames for its own attachment.
                    if (pausedSeek?.transport?.attachment !== attachment) attachment?.bufferingSeek = false
                }
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
        is CoordinatorCommand.LivePriority -> {
            processTimeshiftCommand(command) { controls ->
                controls.setPriority(command.priority)?.toPublicTimeshiftResult()
                    ?: TimeshiftCommandResult.UNAVAILABLE
            }
            false
        }
        is CoordinatorCommand.ContentSeek -> {
            if (command.ticket.claim()) {
                val target = activeTarget as? ActorTarget.Live
                val attachment = target?.timeshiftControls?.mappingAttachment()
                try {
                    attachment?.bufferingSeek =
                        (attachment?.availableState() as? LiveTimeshiftState.Available)?.playbackPaused == true
                    val result = target?.timeshiftControls?.seekContent(command.target, command.selection)
                        ?: TimeshiftContentSeekResult.Replaced
                    val completed = if (target != null && result is TimeshiftContentSeekResult.Completed &&
                        result.command.isAccepted && result.seek != null
                    ) {
                        startPausedSeek(target, result, command, command.reply, scope) { command.reply.complete(it) }
                    } else {
                        result
                    }
                    if (completed != null) {
                        command.ticket.complete()
                        command.reply.complete(completed)
                    }
                } catch (cancellation: CancellationException) {
                    command.ticket.complete()
                    command.reply.completeExceptionally(cancellation)
                    throw cancellation
                } finally {
                    // Pending buffering keeps recording rendered frames for its own attachment.
                    if (pausedSeek?.transport?.attachment !== attachment) attachment?.bufferingSeek = false
                }
            }
            false
        }
        is CoordinatorCommand.ContentPosition -> {
            if (command.ticket.claim()) {
                val target = activeTarget as? ActorTarget.Live
                val attachment = target?.timeshiftControls?.mappingAttachment()
                val snapshot = target?.let { player.snapshot(it.token) }
                command.reply.complete(
                    if (target != null && attachment != null && snapshot != null && !snapshot.failed &&
                        attachment.periodUid != null && snapshot.periodUid == attachment.periodUid
                    ) {
                        target.timeshiftControls.playbackPosition(attachment, snapshot.position, snapshot.positionResolutionUs)
                    } else {
                        TimeshiftPlaybackPosition.Unavailable
                    },
                )
                command.ticket.complete()
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
            if (result.status == PlaybackPlayerInstallStatus.STARTED || result.status == PlaybackPlayerInstallStatus.DEPARTED) {
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
            val finalSubscriptionIssue = applyRetirement(result.retiredTarget, result.retiredRecording)
            if (!result.cancelled) {
                command.reply.complete(
                    when {
                        !result.playerAvailable -> PlaybackStopResult.PlayerUnavailable(finalSubscriptionIssue)
                        result.retiredAfterDeparture -> PlaybackStopResult.RetiredAfterDeparture(finalSubscriptionIssue)
                        result.retiredTarget -> PlaybackStopResult.Stopped(finalSubscriptionIssue)
                        else -> PlaybackStopResult.AlreadyStopped
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

    /**
     * Starts refilling a paused player after an accepted seek, or returns its immediate result.
     *
     * Returns null while buffering continues; the actor keeps processing other work and completes
     * [answer] once readiness, a failure, its timeout, caller cancellation or a superseding command
     * ends it. A cancellation ending it completes [reply] exceptionally instead.
     */
    private suspend fun startPausedSeek(
        target: ActorTarget.Live,
        accepted: TimeshiftContentSeekResult.Completed,
        command: CoordinatorCommand,
        reply: CompletableDeferred<*>,
        scope: CoroutineScope,
        answer: (TimeshiftContentSeekResult.Completed) -> Unit,
    ): TimeshiftContentSeekResult.Completed? {
        val seek = checkNotNull(accepted.seek)
        val controls = target.timeshiftControls
        val unavailable = TimeshiftContentSeekResult.Completed(
            TimeshiftCommandResult.UNAVAILABLE, accepted.readerReached, seek, accepted.command,
            selection = accepted.selection,
        )
        // A restart after the acknowledgement must not refill a period the seek never reached.
        val attachment = controls.mappingAttachment()?.takeIf { it === seek.owner } ?: return unavailable
        if ((attachment.availableState() as? LiveTimeshiftState.Available)?.playbackPaused != true) {
            return accepted
        }
        val transport = controls.bufferingTransport(attachment) ?: return unavailable
        val buffer = PausedSeekBuffer(target, transport, accepted, command, reply, answer)
        // Presentation stays paused while normal transport delivery replenishes both A/V queues.
        val started = controls.startBuffering(transport)?.toPublicTimeshiftResult()
            ?: TimeshiftCommandResult.UNAVAILABLE
        if (!started.isAccepted) return settlePausedSeek(buffer, started, scope)
        pausedSeek = buffer
        buffer.timer = scope.launch {
            withTimeoutOrNull(PAUSED_SEEK_BUFFERING_TIMEOUT) {
                while (true) {
                    buffer.polls.send(false)
                    // The interval starts once the actor handled this poll: a player round trip
                    // longer than the interval must not keep a poll ready ahead of every command.
                    buffer.polled.receive()
                    delay(PAUSED_SEEK_POLL_INTERVAL)
                }
            }
            buffer.polls.send(true)
        }
        return null
    }

    private suspend fun pollPausedSeek(buffer: PausedSeekBuffer, expired: Boolean, scope: CoroutineScope) {
        if (pausedSeek !== buffer) return
        val buffering = if (expired) TimeshiftCommandResult.TIMEOUT else pausedSeekReadiness(buffer)
        if (buffering == null) {
            buffer.polled.trySend(Unit)
            return
        }
        finishPausedSeek(buffer, buffering, scope)
    }

    /** Returns the buffering outcome once known, or null while the paused player is still refilling. */
    private suspend fun pausedSeekReadiness(buffer: PausedSeekBuffer): TimeshiftCommandResult? {
        val target = buffer.target
        val controls = target.timeshiftControls
        val attachment = buffer.transport.attachment
        val seek = checkNotNull(buffer.accepted.seek)
        if (!target.token.isActive() || buffer.command.ticket.isCancellationRequested()) {
            return TimeshiftCommandResult.UNAVAILABLE
        }
        if (controls.mappingAttachment() !== attachment) return TimeshiftCommandResult.UNAVAILABLE
        if (attachment.bufferingCapacitySeek === seek && !attachment.bufferingCapacityAvailable) {
            return TimeshiftCommandResult.UNAVAILABLE
        }
        val snapshot = player.snapshot(target.token) ?: return TimeshiftCommandResult.UNAVAILABLE
        if (snapshot.failed || snapshot.periodUid != attachment.periodUid) return TimeshiftCommandResult.UNAVAILABLE
        val position = controls.playbackPosition(attachment, snapshot.position, snapshot.positionResolutionUs)
        return TimeshiftCommandResult.ACCEPTED.takeIf {
            snapshot.playbackState == Player.STATE_READY &&
                (position as? TimeshiftPlaybackPosition.Estimate)?.seek === seek &&
                (!snapshot.videoSelected || attachment.hasRenderedSeek(seek))
        }
    }

    private suspend fun finishPausedSeek(
        buffer: PausedSeekBuffer,
        buffering: TimeshiftCommandResult,
        scope: CoroutineScope,
        superseded: Boolean = false,
    ) {
        val result = try {
            settlePausedSeek(buffer, buffering, scope, superseded)
        } catch (cancellation: CancellationException) {
            buffer.command.ticket.complete()
            buffer.reply.completeExceptionally(cancellation)
            throw cancellation
        } catch (failure: Exception) {
            buffer.command.ticket.complete()
            buffer.command.rejectAfterShutdown()
            throw failure
        }
        buffer.command.ticket.complete()
        buffer.answer(result)
    }

    /**
     * Answers a seek still buffering when the actor ends, after pausing its transport again as an
     * interrupted seek command does; a cancellation reaches the seek's caller unchanged. A failure
     * of that cleanup is suppressed into [cause] instead of replacing it.
     */
    private suspend fun endPausedSeek(cause: Exception) {
        val buffer = pausedSeek ?: return
        try {
            try {
                settlePausedSeek(buffer, TimeshiftCommandResult.UNAVAILABLE, scope = null)
            } finally {
                buffer.command.ticket.complete()
                if (cause is CancellationException) {
                    buffer.reply.completeExceptionally(cause)
                } else {
                    buffer.command.rejectAfterShutdown()
                }
            }
        } catch (cleanup: Exception) {
            if (cleanup !== cause) cause.addSuppressed(cleanup)
        }
    }

    /**
     * Pauses the buffering transport again and combines [buffering] with that restoration.
     *
     * A [superseded] seek keeps its acknowledgement unless the transport refused the pause, so a
     * transient result never invites replaying a seek that newer intent replaced.
     */
    private suspend fun settlePausedSeek(
        buffer: PausedSeekBuffer,
        buffering: TimeshiftCommandResult,
        scope: CoroutineScope?,
        superseded: Boolean = false,
    ): TimeshiftContentSeekResult.Completed {
        if (pausedSeek === buffer) pausedSeek = null
        buffer.timer?.cancel()
        val restoration = try {
            restorePause(buffer.target, buffer.transport, scope, owedOnly = false)
        } finally {
            buffer.transport.attachment.bufferingSeek = false
        }
        val restored = restoration.toPublicTimeshiftResult()
        val refused = restoration is LiveTimeshiftControlBridge.PauseRestoration.Answered && !restored.isAccepted
        val accepted = buffer.accepted
        return TimeshiftContentSeekResult.Completed(
            when {
                refused -> restored
                superseded -> accepted.command
                restored.isAccepted -> buffering
                else -> restored
            },
            accepted.readerReached, accepted.seek, accepted.command, buffering, restored, accepted.selection,
        )
    }

    /**
     * Pauses [transport] again. A refused pause retires the target, because unsolicited input would
     * fill a paused player's queues; a pause a server stop interrupted is retried once the same
     * subscription restarts, unless [scope] is null because the actor is ending.
     */
    private suspend fun restorePause(
        target: ActorTarget.Live,
        transport: LiveTimeshiftControlBridge.BufferingTransport,
        scope: CoroutineScope?,
        owedOnly: Boolean,
    ): LiveTimeshiftControlBridge.PauseRestoration = withContext(NonCancellable) {
        val controls = target.timeshiftControls
        controls.restorePausedSpeed(transport, owedOnly).also { restoration ->
            when (restoration) {
                LiveTimeshiftControlBridge.PauseRestoration.NotOwned -> Unit
                LiveTimeshiftControlBridge.PauseRestoration.Interrupted ->
                    if (scope != null && activeTarget === target) owePause(target, transport, scope)
                is LiveTimeshiftControlBridge.PauseRestoration.Answered ->
                    if (restoration.result !is SubscriptionOperationResult.Ok && activeTarget === target &&
                        controls.closeIfOwned(transport)
                    ) {
                        val retirement = player.stop(PlayerOperationTicket())
                        applyRetirement(retirement.retiredTarget, retirement.retiredRecording)
                    }
            }
        }
    }

    private fun owePause(
        target: ActorTarget.Live,
        transport: LiveTimeshiftControlBridge.BufferingTransport,
        scope: CoroutineScope,
    ) {
        owedPause?.watcher?.cancel()
        val settled = Channel<Unit>(Channel.CONFLATED)
        val watcher = scope.launch {
            target.timeshiftControls.awaitTransportSettled(transport)
            settled.send(Unit)
        }
        owedPause = OwedPause(target, transport, watcher, settled)
    }

    /** Retries an owed pause once its subscription is playable again; an ended one owes nothing. */
    private suspend fun repayPause(owed: OwedPause, scope: CoroutineScope) {
        if (owedPause !== owed) return
        owedPause = null
        restorePause(owed.target, owed.transport, scope, owedOnly = true)
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
    ): SubscriptionIssue? {
        if (!retiredTarget) return null
        val old = activeTarget
        // A retired target's transport is closed with it and owes no pause.
        owedPause?.takeIf { it.target === old }?.let { owed ->
            owed.watcher.cancel()
            owedPause = null
        }
        val finalSubscriptionIssue = (old as? ActorTarget.Live)?.let { live ->
            val issue = deactivateTimeshift(live.token)
            live.timeshiftControls.retire()
            issue
        }
        if (old is ActorTarget.Recording && retiredRecording?.token === old.token) {
            terminalize(
                target = old,
                snapshot = retiredRecording.snapshot,
                exit = retiredRecording.exit,
                growingFinalEndProven = retiredRecording.growingFinalEndProven,
                allowWatched = retiredRecording.allowWatched,
            )
        }
        ticker?.cancel()
        ticker = null
        activeTarget = null
        return finalSubscriptionIssue
    }

    private suspend fun drainPlayerEvents() {
        while (true) {
            val event = playerEvents.take() ?: return
            val target = activeTarget ?: continue
            if (target.token !== event.token || !event.token.isActive()) continue
            // The application's item replaced the target after the event was published: only a
            // terminal exit the target reached before that still belongs to it.
            val ownsItem = event.token.ownsPlayerItem()
            when (target) {
                is ActorTarget.Live -> event.recoveryReason?.takeIf { ownsItem }?.let { reason ->
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
                    event.paused &&
                        ownsItem &&
                        !target.terminalized &&
                        !event.snapshot.precedesPendingResume &&
                        refreshRecordingTarget(target) ->
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

    /**
     * Ends a live target whose bounded queues filled without server timeshift.
     *
     * Retirement is claimed under the bridge ownership lock on the player's looper. Replacement
     * before the claim invalidates the event; after the claim no successor can bind. Player calls
     * remain outside that lock. Current play intent does not undo exhaustion of the live queues.
     * Application departure before admission rejects retirement without a recovery callback,
     * and settles the original consumer call as a buffer-limit failure outside all player locks.
     */
    private suspend fun endExhaustedLiveTarget(exhaustion: LiveBufferExhaustion) {
        var failed = false
        try {
            failed = retireExhaustedLiveTarget(exhaustion)
        } finally {
            exhaustion.settle(failed)
        }
    }

    /** Fail delivery after departure or incomplete cleanup of a retired target. */
    private suspend fun retireExhaustedLiveTarget(exhaustion: LiveBufferExhaustion): Boolean {
        if (!exhaustion.isCurrent()) return false
        var claimed = false
        val retirement = player.stop(PlayerOperationTicket()) {
            exhaustion.attachment.claimExhaustionRetirement().also { claimed = it }
        }
        applyRetirement(retirement.retiredTarget, retirement.retiredRecording)
        // Conditional retirement also rejects a player item the application has already left.
        if (!retirement.retiredTarget && retirement.playerAvailable && !claimed) {
            return !exhaustion.token.ownsPlayerItem()
        }
        // A claim intentionally ends attachment leadership. Failure after claiming is not stale work.
        if (!retirement.retiredTarget && !claimed && !exhaustion.isCurrent()) return false
        val reason = when {
            !retirement.retiredTarget -> PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED
            retirement.playerStoppedAndCleared -> PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED
            else -> PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED
        }
        try {
            onRecoveryRequired(reason)
        } catch (_: Exception) {
            // Application callbacks must not terminate the coordinator owner.
        }
        return retirement.retiredTarget && !retirement.playerStoppedAndCleared
    }

    private fun LiveBufferExhaustion.isCurrent(): Boolean =
        (activeTarget as? ActorTarget.Live)?.token === token && token.isActive() && attachment.leadsTarget()

    private suspend fun observeElapsed(token: PlaybackTargetToken) {
        val target = activeTarget as? ActorTarget.Recording ?: return
        if (target.token !== token || target.terminalized) return
        if (!refreshRecordingTarget(target)) return
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val snapshot = player.snapshot(token)?.takeUnless { it.precedesPendingResume } ?: return
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
        allowWatched: Boolean = true,
    ) {
        if (target.terminalized) return
        target.terminalized = true
        ticker?.cancel()
        ticker = null
        // Leaving before a pending resume applied keeps the saved server position untouched.
        if (snapshot.precedesPendingResume) return
        if (!refreshRecordingTarget(target)) return
        val tracker = target.tracker ?: return
        val epoch = target.reportEpoch?.takeIf { it.isValid() } ?: return
        val terminalState = if (
            !allowWatched || target.growingLease != null &&
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
        target.reportEpoch = PlaybackReportEpoch(target.token) { reportableEntryState(target) != null }
        player.snapshot(target.token)?.takeUnless { it.precedesPendingResume }?.let { snapshot ->
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
            if (!report.epoch.admits(report.terminal)) continue
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

    /** One accepted paused seek whose transport runs at normal speed until the paused player refills. */
    private class PausedSeekBuffer(
        val target: ActorTarget.Live,
        val transport: LiveTimeshiftControlBridge.BufferingTransport,
        val accepted: TimeshiftContentSeekResult.Completed,
        val command: CoordinatorCommand,
        /** The seek's own reply, completed exceptionally when a cancellation ends buffering. */
        val reply: CompletableDeferred<*>,
        val answer: (TimeshiftContentSeekResult.Completed) -> Unit,
    ) {
        /** Readiness poll requests; true once the buffering timeout expired. */
        val polls = Channel<Boolean>(Channel.CONFLATED)

        /** Signals the timer that the actor handled a poll that found the player still refilling. */
        val polled = Channel<Unit>(Channel.CONFLATED)
        var timer: Job? = null
    }

    /** A paused seek's transport whose pause a server stop interrupted; retried once it is playable. */
    private class OwedPause(
        val target: ActorTarget.Live,
        val transport: LiveTimeshiftControlBridge.BufferingTransport,
        val watcher: Job,
        val settled: Channel<Unit>,
    )

    /** A live period's queues that filled without timeshift, awaiting ownership-checked retirement. */
    private class LiveBufferExhaustion(
        val token: PlaybackTargetToken,
        val attachment: LiveTimeshiftControlBridge.Attachment,
        val settle: (failed: Boolean) -> Unit,
    )

    private companion object {
        val PAUSED_SEEK_BUFFERING_TIMEOUT: Duration = 5.seconds
        val PAUSED_SEEK_POLL_INTERVAL: Duration = 20.milliseconds
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

    data class LivePriority(
        val priority: LiveSubscriptionPriority,
        override val ticket: PlayerOperationTicket,
        override val reply: CompletableDeferred<TimeshiftCommandResult>,
    ) : Timeshift(ticket, reply)

    data class ContentSeek(
        val target: TimeshiftContentTarget,
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<TimeshiftContentSeekResult>,
        val selection: TimeshiftSeekSelection? = null,
    ) : CoordinatorCommand(ticket)

    data class ContentPosition(
        override val ticket: PlayerOperationTicket,
        val reply: CompletableDeferred<TimeshiftPlaybackPosition>,
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

    /** Whether this command ends pending paused-seek buffering; position samples and priority changes do not. */
    val supersedesPausedSeek: Boolean
        get() = this !is ContentPosition && this !is LivePriority

    /** Whether this command can retire the current target, which then closes a buffering transport itself. */
    val mayRetireTarget: Boolean
        get() = this is Live || this is Recording || this is Stop || this is Shutdown

    fun rejectAfterShutdown() {
        when (this) {
            is Live -> reply.complete(PlaybackTargetResult.SHUT_DOWN)
            is Recording -> reply.complete(PlaybackTargetResult.SHUT_DOWN)
            is TimeshiftSeek -> reply.complete(TimeshiftCommandResult.SHUT_DOWN)
            is TimeshiftSpeed -> reply.complete(TimeshiftCommandResult.SHUT_DOWN)
            is LivePriority -> reply.complete(TimeshiftCommandResult.SHUT_DOWN)
            is ContentSeek -> reply.complete(TimeshiftContentSeekResult.Unavailable)
            is ContentPosition -> reply.complete(TimeshiftPlaybackPosition.Unavailable)
            is Stop -> reply.complete(PlaybackStopResult.ShutDown)
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
    CoordinatorLifecycle.NEW -> PlaybackStopResult.NotRunning
    CoordinatorLifecycle.RUNNING -> PlaybackStopResult.ShutDown
    CoordinatorLifecycle.SHUTTING_DOWN,
    CoordinatorLifecycle.STOPPED,
    -> PlaybackStopResult.ShutDown
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

internal fun SubscriptionSeekResult.toPublicTimeshiftResult(): TimeshiftCommandResult = when (this) {
    SubscriptionSeekResult.Accepted -> TimeshiftCommandResult.ACCEPTED
    is SubscriptionSeekResult.AcceptedAt -> TimeshiftCommandResult.ACCEPTED
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
    SubscriptionSeekResult.SegmentUnavailable -> TimeshiftCommandResult.UNAVAILABLE
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
        SubscriptionOperationResult.MalformedResponse -> TimeshiftCommandResult.MALFORMED_RESPONSE
    }

private fun LiveTimeshiftControlBridge.PauseRestoration.toPublicTimeshiftResult(): TimeshiftCommandResult = when (this) {
    LiveTimeshiftControlBridge.PauseRestoration.NotOwned -> TimeshiftCommandResult.UNAVAILABLE
    LiveTimeshiftControlBridge.PauseRestoration.Interrupted -> TimeshiftCommandResult.TRANSPORT_UNAVAILABLE
    is LiveTimeshiftControlBridge.PauseRestoration.Answered -> result.toPublicTimeshiftResult()
}

private fun SubscriptionOperationFailure.toPublicTimeshiftResult(): TimeshiftCommandResult = when (this) {
    SubscriptionOperationFailure.SERVER_REJECTED -> TimeshiftCommandResult.SERVER_REJECTED
    SubscriptionOperationFailure.ACCESS_DENIED -> TimeshiftCommandResult.ACCESS_DENIED
    SubscriptionOperationFailure.CONNECTION_LIMIT -> TimeshiftCommandResult.CONNECTION_LIMIT
    SubscriptionOperationFailure.TIMEOUT -> TimeshiftCommandResult.TIMEOUT
    SubscriptionOperationFailure.TRANSPORT_UNAVAILABLE -> TimeshiftCommandResult.TRANSPORT_UNAVAILABLE
    SubscriptionOperationFailure.NOT_SUPPORTED -> TimeshiftCommandResult.NOT_SUPPORTED
    SubscriptionOperationFailure.MALFORMED_RESPONSE -> TimeshiftCommandResult.MALFORMED_RESPONSE
}

private fun PlaybackPlayerInstallStatus.toPublicTargetResult(): PlaybackTargetResult = when (this) {
    PlaybackPlayerInstallStatus.STARTED -> PlaybackTargetResult.STARTED
    PlaybackPlayerInstallStatus.DEPARTED -> PlaybackTargetResult.DEPARTED
    PlaybackPlayerInstallStatus.NOT_READY -> PlaybackTargetResult.NOT_READY
    PlaybackPlayerInstallStatus.RECORDING_PROGRESS_UNSUPPORTED ->
        PlaybackTargetResult.RECORDING_PROGRESS_UNSUPPORTED
    PlaybackPlayerInstallStatus.TARGET_UNAVAILABLE -> PlaybackTargetResult.TARGET_UNAVAILABLE
    PlaybackPlayerInstallStatus.GROWING_RECORDING_DEFERRED ->
        PlaybackTargetResult.GROWING_RECORDING_DEFERRED
    PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE -> PlaybackTargetResult.PLAYER_UNAVAILABLE
    PlaybackPlayerInstallStatus.CANCELLED -> error("Cancelled player operations have no result")
}
