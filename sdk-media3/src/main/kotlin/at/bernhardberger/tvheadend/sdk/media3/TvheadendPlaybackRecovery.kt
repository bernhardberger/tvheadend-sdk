@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks

/**
 * Timings used to recover a live target that remains stuck in buffering.
 *
 * Recovery disables audio at most once per target and restores it when playback becomes ready or
 * idle. Later stalls use [postAudioDisableDurationMillis] without disabling audio again. Recovery
 * only ever restores a disable it applied itself: audio the application disabled stays disabled.
 */
public data class PlaybackRecoveryPolicy public constructor(
    public val initialBufferingDurationMillis: Long = 6_000L,
    public val postAudioDisableDurationMillis: Long = 6_000L,
    /**
     * Budget for a target that has never presented selected audio.
     *
     * A live subscription buffers without any track for as long as the server needs to tune the
     * mux, descramble it, and deliver the first packets. That is ordinary progress, not a stall,
     * so it must not share the much shorter stuck-buffering budget.
     */
    public val preparationDurationMillis: Long = 20_000L,
) {
    init {
        require(initialBufferingDurationMillis > 0L) {
            "Initial buffering duration must be positive"
        }
        require(postAudioDisableDurationMillis > 0L) {
            "Post-audio-disable duration must be positive"
        }
        require(preparationDurationMillis > 0L) {
            "Preparation duration must be positive"
        }
    }
}

/**
 * Application action required after SDK-owned live recovery is exhausted.
 * Consumers must retain a fallback for future values.
 */
public class PlaybackRecoveryReason private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known live recovery reasons. */
    public companion object {
        /** Buffering continued without selected audio or after selected audio was disabled. */
        @JvmField
        public val AUDIO_RECOVERY_EXHAUSTED: PlaybackRecoveryReason = PlaybackRecoveryReason("AUDIO_RECOVERY_EXHAUSTED")

        /** A live source ended and must be replaced rather than treated as completed media. */
        @JvmField
        public val LIVE_ENDED: PlaybackRecoveryReason = PlaybackRecoveryReason("LIVE_ENDED")

        /**
         * A live target without server timeshift could not advance until the SDK's bounded live sample
         * queues filled. Only [TvheadendPlaybackCoordinator] reports this.
         *
         * This can follow a pause or playback suppression, for example transient audio-focus loss or
         * an unsuitable audio output. Retirement occurs even if playback has since resumed. Timeshift
         * was unavailable, so it could not hold the live stream. Before reporting this, the coordinator
         * has stopped the player and removed the TVHeadend item, which closes the subscription and
         * releases its buffers, and has retired the target. The player stays application-owned.
         *
         * If the application already left the target's item before retirement is admitted, neither
         * exhaustion reason is reported and exhaustion does not mutate the player. The departed target
         * instead fails its period with "Live sample buffer limit reached" and closes its subscription
         * without another event, even if departure raced the exhaustion hand-off. The subscription
         * ends with `ConsumerFailed` or, when the replay of packets buffered before period creation
         * fails first, with `Closed`. Returning to the item
         * does not restore ownership or resume that subscription. The target can remain active until
         * an explicit stop, target change, or shutdown retires it; playing it again requires an explicit
         * [TvheadendPlaybackCoordinator.setLiveTarget].
         *
         * Resuming retunes to live: install the target again with
         * [TvheadendPlaybackCoordinator.setLiveTarget] once playback can advance again, that is when
         * `playWhenReady` is true and `playbackSuppressionReason` is `PLAYBACK_SUPPRESSION_REASON_NONE`
         * (for example from `onPlayWhenReadyChanged` or `onPlaybackSuppressionReasonChanged`). If that
         * already holds when this is reported, retune immediately. Retuning while playback still cannot
         * advance fills the queues again.
         */
        @JvmField
        public val LIVE_BUFFER_EXHAUSTED: PlaybackRecoveryReason = PlaybackRecoveryReason("LIVE_BUFFER_EXHAUSTED")

        /**
         * A live target exhausted its bounded queues, but retirement could not start. The target may
         * still be active: call
         * [TvheadendPlaybackCoordinator.stop] or [TvheadendPlaybackCoordinator.setLiveTarget] to retry.
         * Neither subscription closure, buffer release nor a period error is guaranteed.
         * Only [TvheadendPlaybackCoordinator] reports this.
         */
        @JvmField
        public val LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED: PlaybackRecoveryReason =
            PlaybackRecoveryReason("LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED")

        /**
         * A live target exhausted its bounded queues and was retired, but stopping the player or
         * removing its TVHeadend item could not be confirmed. Buffer release is not guaranteed.
         * Only [TvheadendPlaybackCoordinator] reports this. The target is already retired; cleanup is
         * pending and retried by [TvheadendPlaybackCoordinator.stop] or the next target installation. The period
         * fails and server delivery ends without another event (`ConsumerFailed` or `Closed`), even if
         * cleanup remains pending. If the leftover TVHeadend item is still current,
         * the player may also report "Live sample buffer limit reached" as a player error; it needs no
         * handling beyond this callback. Observe [TvheadendPlaybackCoordinator.playerCleanupPending]
         * and retry stop or installation once the Player fault clears. The same applies after
         * [PlaybackStopResult.playerUnavailable] or [PlaybackTargetResult.PLAYER_UNAVAILABLE].
         * Applications must not infer coordinator item ownership or remove those items themselves.
         */
        @JvmField
        public val LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED: PlaybackRecoveryReason =
            PlaybackRecoveryReason("LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED")
    }
}

/**
 * Coordinates recovery for one application-owned [Player].
 *
 * Call [beginPlaybackTarget] on the player's application looper immediately before installing
 * each new TVHeadend live source. The recovery callback must promptly retire or replace that
 * source. Closing this coordinator never releases the player.
 *
 * The audio track type in the player's track selection parameters stays application-owned.
 * Recovery re-enables audio only after disabling it itself, and gives that disable up when the
 * application re-enables audio meanwhile. A disable that was already in place when recovery would
 * have applied its own is never undone.
 */
public class TvheadendPlaybackRecovery internal constructor(
    private val player: Player,
    policy: PlaybackRecoveryPolicy,
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    isTargetCurrent: () -> Boolean = { true },
    scheduler: RecoveryScheduler? = null,
) : AutoCloseable {
    private val handler = Handler(player.applicationLooper)
    private val stateMachine = PlaybackRecoveryStateMachine(
        policy = policy,
        scheduler = scheduler ?: HandlerRecoveryScheduler(handler),
        hasSelectedAudio = { player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) },
        isAudioDisabled = { C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes },
        setAudioDisabled = { disabled ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled)
                .build()
        },
        onRecoveryRequired = onRecoveryRequired,
        playWhenReady = player.playWhenReady,
        isTargetCurrent = isTargetCurrent,
    )
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            stateMachine.onCurrentItemChanged()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            stateMachine.onCurrentItemChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            stateMachine.onPlaybackStateChanged(playbackState)
        }

        override fun onTracksChanged(tracks: Tracks) {
            stateMachine.onTracksChanged()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            stateMachine.onPlayWhenReadyChanged(playWhenReady)
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            stateMachine.onAudioDisabledChanged(C.TRACK_TYPE_AUDIO in parameters.disabledTrackTypes)
        }
    }
    private var closed = false

    init {
        requirePlayerLooper()
        player.addListener(listener)
    }

    /** Starts a new live target generation, restoring audio only if recovery disabled it. */
    public fun beginPlaybackTarget() {
        requirePlayerLooper()
        check(!closed) { "Playback recovery is closed" }
        stateMachine.beginPlaybackTarget()
    }

    /** Detaches recovery without releasing the application-owned player. */
    override fun close() {
        requirePlayerLooper()
        if (closed) return
        closed = true
        try {
            player.removeListener(listener)
        } finally {
            stateMachine.close()
        }
    }

    private fun requirePlayerLooper() {
        check(Looper.myLooper() === player.applicationLooper) {
            "Playback recovery must be called on the player's application looper"
        }
    }
}

/** Creates one recovery coordinator for an application-owned [Player]. */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendPlaybackRecovery(
    player: Player,
    policy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
): TvheadendPlaybackRecovery = TvheadendPlaybackRecovery(player, policy, onRecoveryRequired)

internal fun interface ScheduledRecoveryTask {
    fun cancel()
}

internal fun interface RecoveryScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask
}

private class HandlerRecoveryScheduler(private val handler: Handler) : RecoveryScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask {
        val runnable = Runnable(action)
        check(handler.postDelayed(runnable, delayMillis)) { "Player looper is not accepting work" }
        return ScheduledRecoveryTask { handler.removeCallbacks(runnable) }
    }
}

internal class PlaybackRecoveryStateMachine(
    private val policy: PlaybackRecoveryPolicy,
    private val scheduler: RecoveryScheduler,
    private val hasSelectedAudio: () -> Boolean,
    private val isAudioDisabled: () -> Boolean,
    private val setAudioDisabled: (Boolean) -> Unit,
    private val onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    private var playWhenReady: Boolean = true,
    private val isTargetCurrent: () -> Boolean = { true },
) {
    private var targetGeneration = 0L
    private var timerGeneration = 0L
    private var playbackState = Player.STATE_IDLE
    private var timerStage: TimerStage? = null
    private var timer: ScheduledRecoveryTask? = null
    private var active = false
    private var terminal = false

    /** True only while audio is disabled by this recovery; no other disable is ever undone. */
    private var audioDisabled = false
    private var audioRecoveryAttempted = false
    private var sawSelectedAudio = false

    fun beginPlaybackTarget() {
        targetGeneration += 1L
        cancelTimer()
        restoreAudio()
        playbackState = Player.STATE_IDLE
        active = true
        terminal = false
        audioRecoveryAttempted = false
        sawSelectedAudio = false
    }

    fun onPlaybackStateChanged(state: Int) {
        if (!active || !targetCurrent() || terminal) return
        playbackState = state
        observeSelectedAudio()
        when (state) {
            Player.STATE_BUFFERING -> evaluateBuffering()
            Player.STATE_READY, Player.STATE_IDLE -> {
                cancelTimer()
                restoreAudio()
            }
            Player.STATE_ENDED -> escalate(PlaybackRecoveryReason.LIVE_ENDED)
        }
    }

    fun onTracksChanged() {
        if (!active || !targetCurrent() || terminal) return
        // Audio can be selected while the target is ready and lost again before the next stall.
        // Latch it whenever it is seen, even though timers are only armed while buffering.
        observeSelectedAudio()
        if (playbackState != Player.STATE_BUFFERING) return
        evaluateBuffering()
    }

    fun onPlayWhenReadyChanged(value: Boolean) {
        playWhenReady = value
        if (!active || !targetCurrent() || terminal) return
        if (!value) cancelTimer()
        else if (playbackState == Player.STATE_BUFFERING) evaluateBuffering()
    }

    /** Stands down as soon as the player's current item stops being the target's own. */
    fun onCurrentItemChanged() {
        if (active) targetCurrent()
    }

    /** Observes the player's audio-disabled flag; an application re-enabling audio takes the disable back. */
    fun onAudioDisabledChanged(disabled: Boolean) {
        if (!disabled) audioDisabled = false
    }

    fun close() {
        active = false
        terminal = true
        cancelTimer()
        restoreAudio()
    }

    /**
     * Whether the target's own item is still current. Otherwise recovery stands down for an item it
     * does not own: it arms nothing, escalates nothing, and undoes its own audio disable.
     */
    private fun targetCurrent(): Boolean {
        if (isTargetCurrent()) return true
        cancelTimer()
        restoreAudio()
        return false
    }

    private fun restoreAudio() {
        if (audioDisabled) {
            audioDisabled = false
            setAudioDisabled(false)
        }
    }

    private fun observeSelectedAudio() {
        if (hasSelectedAudio()) sawSelectedAudio = true
    }

    private fun evaluateBuffering() {
        // A paused HTSP seek may deliver video without the audio needed to leave BUFFERING.
        // Waiting for explicit resume is not a stalled playing target.
        if (!playWhenReady) {
            cancelTimer()
            return
        }
        observeSelectedAudio()
        when {
            // Restoring audio must not refill the disable budget and start a mute/unmute loop.
            audioRecoveryAttempted -> schedule(TimerStage.POST_AUDIO_DISABLE)
            // A target that has not presented audio yet is still being tuned and prepared.
            // Only a target that once had audio is treated as stuck on the short budget.
            sawSelectedAudio -> schedule(TimerStage.INITIAL_BUFFERING)
            else -> schedule(TimerStage.PREPARATION)
        }
    }

    private fun schedule(stage: TimerStage) {
        if (timerStage == stage) return
        cancelTimer()
        val expectedTarget = targetGeneration
        val expectedTimer = timerGeneration
        timerStage = stage
        val delayMillis = when (stage) {
            TimerStage.PREPARATION -> policy.preparationDurationMillis
            TimerStage.INITIAL_BUFFERING -> policy.initialBufferingDurationMillis
            TimerStage.POST_AUDIO_DISABLE -> policy.postAudioDisableDurationMillis
        }
        timer = scheduler.schedule(delayMillis) {
            if (
                active &&
                !terminal &&
                playWhenReady &&
                targetGeneration == expectedTarget &&
                timerGeneration == expectedTimer &&
                playbackState == Player.STATE_BUFFERING &&
                targetCurrent()
            ) {
                timer = null
                timerStage = null
                when (stage) {
                    TimerStage.PREPARATION,
                    TimerStage.POST_AUDIO_DISABLE,
                    -> escalate(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED)
                    TimerStage.INITIAL_BUFFERING -> completeInitialRecovery()
                }
            }
        }
    }

    private fun completeInitialRecovery() {
        if (audioRecoveryAttempted) return
        if (!hasSelectedAudio()) {
            escalate(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED)
            return
        }
        audioRecoveryAttempted = true
        // Audio the application already disabled is not recovery's to re-enable later.
        audioDisabled = !isAudioDisabled()
        // Install the timer first: changing selection can synchronously report READY or retire
        // the target, and those callbacks must be able to cancel this attempt completely.
        schedule(TimerStage.POST_AUDIO_DISABLE)
        if (audioDisabled) setAudioDisabled(true)
    }

    private fun escalate(reason: PlaybackRecoveryReason) {
        if (terminal) return
        terminal = true
        cancelTimer()
        onRecoveryRequired(reason)
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        timerStage = null
        timerGeneration += 1L
    }

    private enum class TimerStage {
        PREPARATION,
        INITIAL_BUFFERING,
        POST_AUDIO_DISABLE,
    }
}
