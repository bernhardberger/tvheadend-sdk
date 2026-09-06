@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks

/** Timings used to recover a live target that remains stuck in buffering. */
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

/** The application action required after SDK-owned live playback recovery is exhausted. */
public enum class PlaybackRecoveryReason {
    /** Buffering continued without selected audio or after selected audio was disabled. */
    AUDIO_RECOVERY_EXHAUSTED,

    /** A live source ended and must be replaced rather than treated as completed media. */
    LIVE_ENDED,
}

/**
 * Coordinates recovery for one application-owned [Player].
 *
 * Call [beginPlaybackTarget] on the player's application looper immediately before installing
 * each new TVHeadend live source. The recovery callback must promptly retire or replace that
 * source. Closing this coordinator never releases the player.
 */
public class TvheadendPlaybackRecovery internal constructor(
    private val player: Player,
    policy: PlaybackRecoveryPolicy,
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
) : AutoCloseable {
    private val handler = Handler(player.applicationLooper)
    private val stateMachine = PlaybackRecoveryStateMachine(
        policy = policy,
        scheduler = HandlerRecoveryScheduler(handler),
        hasSelectedAudio = { player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) },
        setAudioDisabled = { disabled ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled)
                .build()
        },
        onRecoveryRequired = onRecoveryRequired,
    )
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            stateMachine.onPlaybackStateChanged(playbackState)
        }

        override fun onTracksChanged(tracks: Tracks) {
            stateMachine.onTracksChanged()
        }
    }
    private var closed = false

    init {
        requirePlayerLooper()
        player.addListener(listener)
    }

    /** Starts a new live target generation and re-enables audio for it. */
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
        player.removeListener(listener)
        stateMachine.close()
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
    private val setAudioDisabled: (Boolean) -> Unit,
    private val onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
) {
    private var targetGeneration = 0L
    private var timerGeneration = 0L
    private var playbackState = Player.STATE_IDLE
    private var timerStage: TimerStage? = null
    private var timer: ScheduledRecoveryTask? = null
    private var active = false
    private var terminal = false
    private var audioDisabled = false
    private var sawSelectedAudio = false

    fun beginPlaybackTarget() {
        targetGeneration += 1L
        cancelTimer()
        playbackState = Player.STATE_IDLE
        active = true
        terminal = false
        audioDisabled = false
        sawSelectedAudio = false
        setAudioDisabled(false)
    }

    fun onPlaybackStateChanged(state: Int) {
        if (!active || terminal) return
        playbackState = state
        observeSelectedAudio()
        when (state) {
            Player.STATE_BUFFERING -> evaluateBuffering()
            Player.STATE_READY, Player.STATE_IDLE -> cancelTimer()
            Player.STATE_ENDED -> escalate(PlaybackRecoveryReason.LIVE_ENDED)
        }
    }

    fun onTracksChanged() {
        if (!active || terminal) return
        // Audio can be selected while the target is ready and lost again before the next stall.
        // Latch it whenever it is seen, even though timers are only armed while buffering.
        observeSelectedAudio()
        if (playbackState != Player.STATE_BUFFERING) return
        evaluateBuffering()
    }

    fun close() {
        active = false
        terminal = true
        cancelTimer()
        if (audioDisabled) {
            audioDisabled = false
            setAudioDisabled(false)
        }
    }

    private fun observeSelectedAudio() {
        if (hasSelectedAudio()) sawSelectedAudio = true
    }

    private fun evaluateBuffering() {
        observeSelectedAudio()
        when {
            audioDisabled -> schedule(TimerStage.POST_AUDIO_DISABLE)
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
                targetGeneration == expectedTarget &&
                timerGeneration == expectedTimer &&
                playbackState == Player.STATE_BUFFERING
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
        if (audioDisabled) return
        if (!hasSelectedAudio()) {
            escalate(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED)
            return
        }
        audioDisabled = true
        setAudioDisabled(true)
        schedule(TimerStage.POST_AUDIO_DISABLE)
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
