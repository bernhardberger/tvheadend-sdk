@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.content.Context
import android.os.Handler
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Creates renderers that prefer platform codecs and use the bundled FFmpeg audio decoder only
 * when no platform renderer supports the format.
 * Includes finite paused H.264 IDR draining for the SDK live sample streams.
 */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendRenderersFactory(context: Context): RenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            out.add(object : MediaCodecVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            ) {
                override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
                    super.onQueueInputBuffer(buffer)
                    (stream as? FinitePreviewSampleStream)?.inputQueued()
                }

                override fun shouldReinitCodec(): Boolean =
                    // The next feed invocation follows successful submission of onQueueInputBuffer's sample.
                    (stream as? FinitePreviewSampleStream)?.drainAndRewind() == true || super.shouldReinitCodec()

                override fun processOutputBuffer(
                    positionUs: Long, elapsedRealtimeUs: Long, codec: MediaCodecAdapter?, buffer: java.nio.ByteBuffer?,
                    bufferIndex: Int, bufferFlags: Int, sampleCount: Int, bufferPresentationTimeUs: Long,
                    isDecodeOnlyBuffer: Boolean, isLastBuffer: Boolean, format: Format,
                ): Boolean {
                    if ((stream as? FinitePreviewSampleStream)?.outputAllowed() == false) {
                        skipOutputBuffer(checkNotNull(codec), bufferIndex, bufferPresentationTimeUs - outputStreamOffsetUs)
                        return true
                    }
                    return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
                        bufferFlags, sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format)
                }
            })
        }
    }
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

internal interface FinitePreviewSampleStream {
    fun outputAllowed(): Boolean
    fun inputQueued()
    fun drainAndRewind(): Boolean
}
