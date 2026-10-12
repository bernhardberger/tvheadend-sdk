package at.bernhardberger.tvheadend.sdk.consumer

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.media3.TvheadendLiveStartBuffer
import at.bernhardberger.tvheadend.sdk.media3.createTvheadendLoadControl
import kotlin.time.Duration.Companion.milliseconds

/** Deliberately no Media3 UnstableApi annotation or opt-in. */
@OptIn(ExperimentalTvheadendApi::class)
internal fun configureLiveStartup(builder: ExoPlayer.Builder): ExoPlayer.Builder {
    val buffer = TvheadendLiveStartBuffer(500.milliseconds)
    val handle = createTvheadendLoadControl(buffer)
    buffer.duration = 750.milliseconds
    return handle.configure(builder)
}
