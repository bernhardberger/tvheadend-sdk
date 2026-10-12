package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
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
