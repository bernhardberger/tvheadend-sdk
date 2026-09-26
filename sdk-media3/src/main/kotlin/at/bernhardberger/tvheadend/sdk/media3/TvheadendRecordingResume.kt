@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Applies one recording resume position to an application-owned [Player].
 *
 * Call [beginPlaybackTarget] after installing a TVHeadend recording media item and before preparing
 * it. Resume remains pending until the selected recording exposes a known, seekable timeline, which
 * keeps progressive MP4 and MKV playback from losing an early seek while their extractors discover
 * the seek map, and keeps growing MPEG-TS playback from seeking before its PCR extent is probed.
 * Closing this coordinator never releases or changes the player.
 */
internal class TvheadendRecordingResume(
    private val player: RecordingResumePlayer,
    private val identity: RecordingMediaIdentity,
    private val mode: RecordingResumeMode = RecordingResumeMode.COMPLETED,
) : AutoCloseable {
    internal constructor(player: Player, identity: RecordingMediaIdentity, mode: RecordingResumeMode) :
        this(Media3RecordingResumePlayer(player), identity, mode)

    private val stateMachine = RecordingResumeStateMachine(
        seekTo = player::seekTo,
        scheduleTimeout = player::postDelayed,
    )
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            requirePlayerLooper()
            evaluateResume()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            requirePlayerLooper()
            evaluateResume()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            requirePlayerLooper()
            if (reason == Player.DISCONTINUITY_REASON_SEEK) stateMachine.onViewerSeek()
        }
    }
    private var closed = false

    init {
        requirePlayerLooper()
        player.addListener(listener)
    }

    /** The saved position that playback has not reached yet, or null once resume settled. */
    internal val pendingPosition: Duration?
        get() {
            requirePlayerLooper()
            return stateMachine.pendingPositionMillis?.milliseconds
        }

    /** The pending saved position that progress reports must not undercut, or null. */
    internal val progressFloor: Duration?
        get() {
            requirePlayerLooper()
            return stateMachine.progressFloorMillis?.milliseconds
        }

    /** How the most recent resume target settled, or null while pending or without a target. */
    internal val outcome: RecordingResumeOutcome?
        get() {
            requirePlayerLooper()
            return stateMachine.outcome
        }

    /**
     * Replaces any pending target with its optional [resumePosition].
     *
     * A null or zero position starts over. For a completed file, a position at or beyond the
     * eventual media duration also starts over rather than issuing an invalid seek; for a growing
     * file it is clamped to [GROWING_RESUME_EDGE_MARGIN_MILLIS] before the known extent. The seek is
     * applied at most once.
     */
    internal fun beginPlaybackTarget(resumePosition: Duration?) {
        requirePlayerLooper()
        check(!closed) { "Recording resume is closed" }
        check(currentTargetMatches()) {
            "Recording resume target must already be installed on the player"
        }
        val positionMillis = resumePosition?.let { position ->
            require(
                position.isFinite() &&
                    !position.isNegative() &&
                    position == position.inWholeMilliseconds.milliseconds,
            ) {
                "Recording resume position must be a finite non-negative whole-millisecond duration"
            }
            position.inWholeMilliseconds
        }
        stateMachine.beginPlaybackTarget(positionMillis, mode)
        evaluateResume()
    }

    /** Detaches resume handling without releasing the application-owned player. */
    override fun close() {
        requirePlayerLooper()
        if (closed) return
        closed = true
        player.removeListener(listener)
        stateMachine.close()
    }

    private fun evaluateResume() {
        val durationMillis = player.duration.takeIf { duration ->
            duration != C.TIME_UNSET && duration >= 0L
        }
        stateMachine.onMediaState(
            currentTargetMatches(),
            durationMillis,
            player.isCurrentMediaItemSeekable,
        )
    }

    private fun currentTargetMatches(): Boolean = player.currentMediaItem?.let { mediaItem ->
        mediaItem.mediaId == identity.uri ||
            mediaItem.localConfiguration?.uri?.toString() == identity.uri
    } == true

    private fun requirePlayerLooper() {
        player.requireApplicationLooper()
    }
}

internal fun createTvheadendRecordingResume(
    player: Player,
    identity: RecordingMediaIdentity,
    mode: RecordingResumeMode = RecordingResumeMode.COMPLETED,
): TvheadendRecordingResume = TvheadendRecordingResume(player, identity, mode)

/** Which timeline rule decides when and where a pending resume seeks. */
internal enum class RecordingResumeMode(
    val timeoutMillis: Long,
) {
    /**
     * A finished file: its known duration is final, so a position at or past it starts over.
     *
     * The seek stays pending until the timeline is seekable or the resume is cancelled; its timeout
     * only ends the progress floor.
     */
    COMPLETED(COMPLETED_RESUME_PROGRESS_FLOOR_TIMEOUT_MILLIS),

    /**
     * A growing MPEG-TS file: the map turns seekable after probing and its extent keeps growing.
     *
     * Its timeout abandons the resume.
     */
    GROWING(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS),
}

/** Terminal state of one resume target; each target settles exactly once. */
internal enum class RecordingResumeOutcome {
    /** The resume seek was issued. */
    APPLIED,

    /** The viewer sought, the target was replaced or stopped, or resume handling was closed. */
    CANCELLED,

    /** The media never offered a usable seekable timeline, so playback continues from the start. */
    ABANDONED,
}

/**
 * Distance kept from the probed end of a growing recording.
 *
 * The extent is the span between the first and last PCR, while playback positions follow PTS. PTS
 * may trail PCR by up to about one second of decoder buffering, and saved server positions are
 * truncated to whole seconds, so a saved position can sit up to about two seconds past what the
 * probed extent proves playable. One extra second keeps the seek clear of the incomplete tail.
 */
internal const val GROWING_RESUME_EDGE_MARGIN_MILLIS: Long = 3_000L

/**
 * Bound for a growing resume to find a seekable timeline.
 *
 * The extent probe starts when the extractor initializes and bounds each sample to five seconds,
 * then retries five seconds after a failed sample. Two complete probe attempts therefore finish
 * within 15 seconds; another five seconds cover opening the file and reading the first keyframe
 * that validates the codec. A map that is still unseekable after that is not going to resume.
 */
internal const val GROWING_RESUME_SETTLE_TIMEOUT_MILLIS: Long = 20_000L

/**
 * How long a pending completed resume withholds earlier progress reports.
 *
 * Completed files normally seek as soon as their seek map is known. For media that stays
 * unseekable longer, progress reports the real position again after this backstop, as before
 * resume suppression existed, while the seek itself stays pending until the timeline is seekable.
 */
internal const val COMPLETED_RESUME_PROGRESS_FLOOR_TIMEOUT_MILLIS: Long = 60_000L

internal class RecordingResumeStateMachine(
    private val seekTo: (Long) -> Unit,
    private val scheduleTimeout: (Long, () -> Unit) -> AutoCloseable,
) {
    private var pending: PendingResume? = null
    private var closed = false

    var outcome: RecordingResumeOutcome? = null
        private set

    val pendingPositionMillis: Long?
        get() = pending?.positionMillis

    /** The pending position while it still withholds earlier progress reports. */
    val progressFloorMillis: Long?
        get() = pending?.takeIf { it.holdsProgress }?.positionMillis

    fun beginPlaybackTarget(positionMillis: Long?, mode: RecordingResumeMode) {
        check(!closed) { "Recording resume is closed" }
        settle(RecordingResumeOutcome.CANCELLED)
        outcome = null
        val position = positionMillis?.takeIf { it > 0L } ?: return
        val target = PendingResume(position, mode)
        pending = target
        target.timeout = scheduleTimeout(mode.timeoutMillis) {
            if (pending === target) onTimeout(target)
        }
    }

    fun onMediaState(
        targetMatches: Boolean,
        durationMillis: Long?,
        isSeekable: Boolean,
    ) {
        val target = pending ?: return
        if (!targetMatches || durationMillis == null) return
        when (target.mode) {
            RecordingResumeMode.COMPLETED -> when {
                target.positionMillis >= durationMillis -> settle(RecordingResumeOutcome.ABANDONED)
                isSeekable -> apply(target.positionMillis)
            }
            RecordingResumeMode.GROWING -> {
                if (!isSeekable) return
                val playableEnd = durationMillis - GROWING_RESUME_EDGE_MARGIN_MILLIS
                // An extent that does not yet cover the margin waits for the next probe refresh.
                if (playableEnd <= 0L) return
                apply(minOf(target.positionMillis, playableEnd))
            }
        }
    }

    private fun onTimeout(target: PendingResume) {
        when (target.mode) {
            RecordingResumeMode.COMPLETED -> {
                target.holdsProgress = false
                target.timeout = null
            }
            RecordingResumeMode.GROWING -> settle(RecordingResumeOutcome.ABANDONED)
        }
    }

    fun onViewerSeek() {
        settle(RecordingResumeOutcome.CANCELLED)
    }

    fun close() {
        if (closed) return
        settle(RecordingResumeOutcome.CANCELLED)
        closed = true
    }

    private fun apply(positionMillis: Long) {
        settle(RecordingResumeOutcome.APPLIED)
        seekTo(positionMillis)
    }

    private fun settle(settled: RecordingResumeOutcome) {
        val target = pending ?: return
        pending = null
        target.timeout?.close()
        outcome = settled
    }

    private class PendingResume(
        val positionMillis: Long,
        val mode: RecordingResumeMode,
    ) {
        var timeout: AutoCloseable? = null
        var holdsProgress = true
    }
}

internal interface RecordingResumePlayer {
    public val currentMediaItem: MediaItem?
    public val duration: Long
    public val isCurrentMediaItemSeekable: Boolean

    public fun addListener(listener: Player.Listener)

    public fun removeListener(listener: Player.Listener)

    public fun seekTo(positionMillis: Long)

    /** Runs [action] on the application looper after [delayMillis]; closing cancels it. */
    public fun postDelayed(delayMillis: Long, action: () -> Unit): AutoCloseable

    public fun requireApplicationLooper()
}

private class Media3RecordingResumePlayer(
    private val player: Player,
) : RecordingResumePlayer {
    private val handler = Handler(player.applicationLooper)

    override val currentMediaItem: MediaItem?
        get() = player.currentMediaItem
    override val duration: Long
        get() = player.duration
    override val isCurrentMediaItemSeekable: Boolean
        get() = player.isCurrentMediaItemSeekable

    override fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }

    override fun seekTo(positionMillis: Long) {
        player.seekTo(positionMillis)
    }

    override fun postDelayed(delayMillis: Long, action: () -> Unit): AutoCloseable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return AutoCloseable { handler.removeCallbacks(runnable) }
    }

    override fun requireApplicationLooper() {
        check(Looper.myLooper() === player.applicationLooper) {
            "Recording resume must be called on the player's application looper"
        }
    }
}
