@file:androidx.media3.common.util.UnstableApi

package androidx.media3.decoder.ffmpeg

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Stands in for an application-supplied Media3 FFmpeg audio extension. Media3 1.11.0
 * `DefaultRenderersFactory.buildAudioRenderers` loads this class name reflectively through a
 * public `(Handler, AudioRendererEventListener, AudioSink)` constructor, so both stay public.
 */
public class FfmpegAudioRenderer(
    @Suppress("UNUSED_PARAMETER") eventHandler: Handler?,
    @Suppress("UNUSED_PARAMETER") eventListener: AudioRendererEventListener?,
    @Suppress("UNUSED_PARAMETER") audioSink: AudioSink,
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    override fun getName(): String = "FfmpegAudioRenderer"

    override fun supportsFormat(format: Format): Int =
        RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

    override fun render(positionUs: Long, elapsedRealtimeUs: Long): Unit = Unit

    override fun isReady(): Boolean = false

    override fun isEnded(): Boolean = true
}
