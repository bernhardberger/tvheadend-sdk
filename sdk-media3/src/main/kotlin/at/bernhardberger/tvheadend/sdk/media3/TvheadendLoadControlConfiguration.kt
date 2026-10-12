package at.bernhardberger.tvheadend.sdk.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Mutable live startup threshold; ordinary rebuffers retain the SDK's 1.5 s threshold.
 *
 * Settings or application-owned learning may update [duration] on any thread. Volatile publication
 * makes the new value visible to the next playback-thread decision. The SDK does not learn values.
 * The default is 2 s; applications migrating a 500 ms initial policy can pass `500.milliseconds`.
 *
 * Live startup uses a time threshold, without the delegate's byte-target startup escape. Loading
 * decisions remain delegated, but its 15 s / 30 s recording buffer limits are not backpressure on
 * pushed live subscriptions. Prefer small, seconds-scale thresholds: large values can prolong
 * startup or prevent it if the stream cannot accumulate enough buffered media. Reduce [duration]
 * to recover without rebuilding the Player; the allowed integer range is not a practical buffer budget.
 *
 * @throws IllegalArgumentException if [initial] is not finite, non-negative, whole milliseconds
 * fitting the Media3 integer millisecond range.
 */
@ExperimentalTvheadendApi
public class TvheadendLiveStartBuffer(initial: Duration = 2.seconds) {
    @Volatile private var milliseconds: Long = validatedMilliseconds(initial)

    /**
     * Threshold read at each live startup decision, without rebuilding the Player.
     * @throws IllegalArgumentException for a non-finite, negative, fractional-millisecond or
     * out-of-range value. A rejected update leaves the previous value intact.
     */
    @ExperimentalTvheadendApi
    public var duration: Duration
        get() = milliseconds.milliseconds
        set(value) { milliseconds = validatedMilliseconds(value) }

    @ExperimentalTvheadendApi
    override public fun toString(): String = "TvheadendLiveStartBuffer"

    private fun validatedMilliseconds(value: Duration): Long {
        require(value.isFinite() && value >= Duration.ZERO &&
            value.inWholeMilliseconds <= Int.MAX_VALUE && value == value.inWholeMilliseconds.milliseconds) {
            "Live start buffer requires finite non-negative integer milliseconds"
        }
        return value.inWholeMilliseconds
    }
}

/**
 * SDK-owned load-control handle for one application-owned Player/coordinator pair.
 * Sharing a handle across Players is unsupported. The application builds and releases its Player.
 * This handle exposes no Media3 load-control callbacks or allocation internals.
 *
 * Coordinator skip marks are armed on the Player application looper. Wire settlement is timestamped
 * and atomically published on the coordinator actor immediately, independent of that looper's queue.
 * Marks are atomically consumed on the playback thread. Target and period fencing prevents late
 * results from rearming a consumed or replaced mark. Scratch timeline objects belong exclusively
 * to the playback thread; timestamps use Android elapsed realtime, matching Media3's rebuffer clock.
 */
@OptIn(UnstableApi::class)
@ExperimentalTvheadendApi
public class TvheadendLoadControl internal constructor(internal val delegate: LiveStartLoadControl) {
    /** Installs the SDK delegate and returns the same application-created [builder]. */
    @ExperimentalTvheadendApi
    public fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder = builder.setLoadControl(delegate)

    @ExperimentalTvheadendApi
    override public fun toString(): String = "TvheadendLoadControl"
}

/**
 * Creates a handle with mutable startup buffering for SDK-marked live periods only.
 * Recordings (including growing/dynamic recordings), unknown periods and ordinary rebuffers
 * preserve the existing SDK load-control policy. No Player is constructed or owned here.
 */
@OptIn(UnstableApi::class)
@ExperimentalTvheadendApi
public fun createTvheadendLoadControl(liveStartBuffer: TvheadendLiveStartBuffer): TvheadendLoadControl =
    TvheadendLoadControl(LiveStartLoadControl(createTvheadendLoadControl(), liveStartBuffer))

/**
 * Creates a coordinator using the same [loadControl] handle that configured [player]'s builder.
 * Actual server skips (including return-to-live) use the live-start buffer for one restart, while
 * in flight or when that restart begins within 3 s of completion. An existing rebuffer is eligible.
 * Refusal, cancellation, departure, target replacement and teardown clear the mark. Unconfirmed
 * dispatched commands retain the bounded grace according to their typed disposition.
 * The application retains Player ownership, learning, settings and autoplay policy.
 */
@OptIn(UnstableApi::class)
@ExperimentalTvheadendApi
public fun createTvheadendPlaybackCoordinator(
    player: ExoPlayer,
    loadControl: TvheadendLoadControl,
    progressPolicy: DvrProgressPolicy = DvrProgressPolicy(),
    recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit = {},
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): TvheadendPlaybackCoordinator {
    val events = PlaybackPlayerEventAccumulator()
    return TvheadendPlaybackCoordinator(
        player = Media3PlaybackCoordinatorPlayer(
            ExoPlayerCoordinatorPlaybackAccess(player, recoveryPolicy, onUnsupportedStream),
            events, loadControl.delegate, ::admitRecordingTarget,
        ),
        playerEvents = events,
        progressPolicy = progressPolicy,
        onRecoveryRequired = onRecoveryRequired,
        timeSource = SystemPlaybackCoordinatorTimeSource,
    )
}
