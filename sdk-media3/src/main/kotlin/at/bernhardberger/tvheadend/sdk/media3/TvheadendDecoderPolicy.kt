@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Creates renderers that prefer platform codecs. An application-supplied Media3 extension audio
 * decoder, such as the FFmpeg audio extension, is used only when no platform renderer supports the
 * format. Video extension renderers (libvpx, libdav1d, FFmpeg video) are not installed. The SDK
 * bundles no extension decoder.
 * Includes finite paused H.264 sample draining for the SDK live sample streams. A tunnelled video
 * renderer returns no output buffers, so it neither drains nor gates output. It requests tunnel
 * peek to show the first decodable picture while paused and reports the tunnel's rendered frames.
 * From API 31, while paused, it also reports a key frame handed to the decoder, because some devices
 * never report a peeked frame. That report is made before the codec accepts the frame and does not
 * mean that it was decoded or drawn.
 */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendRenderersFactory(
    context: Context,
    audioOutputProvider: TvheadendAudioOutputProvider = TvheadendAudioOutputProvider(context),
): RenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParameters: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioOutputProvider(audioOutputProvider)
            .build()

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
            out.add(TvheadendVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            ))
        }
    }
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

/**
 * Platform-codec video renderer that drives finite paused H.264 previews and reports rendered
 * frames to SDK live sample streams. Other streams render exactly as [MediaCodecVideoRenderer].
 */
internal class TvheadendVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : MediaCodecVideoRenderer(builder) {
    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        val live = stream as? FinitePreviewSampleStream ?: return
        live.inputQueued()
        // Some tunnelled decoders never report a peeked frame while paused. From API 31, where peek is
        // requested, handing a key frame to the codec stands in for that report. This runs before
        // the codec accepts the buffer, and does not prove that the frame was decoded or drawn.
        if (configuration.tunneling && sdkInt >= Build.VERSION_CODES.S && state == Renderer.STATE_ENABLED &&
            buffer.isKeyFrame
        ) {
            // The read stream's offset, which readSource added to this buffer's timestamp.
            live.frameRendered(buffer.timeUs - streamOffsetUs, PausedSeekConfirmation.KEY_FRAME_QUEUED)
        }
    }

    override fun onStreamChanged(
        formats: Array<Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        // A stream installed during playback never sees this renderer start.
        (stream as? FinitePreviewSampleStream)?.rendererStarted(state == Renderer.STATE_STARTED)
    }

    override fun onStarted() {
        super.onStarted()
        (stream as? FinitePreviewSampleStream)?.rendererStarted(true)
    }

    override fun onStopped() {
        super.onStopped()
        (stream as? FinitePreviewSampleStream)?.rendererStarted(false)
    }

    override fun shouldReinitCodec(): Boolean {
        val finite = stream as? FinitePreviewSampleStream
        if (configuration.tunneling) {
            // Draining needs dequeued output, and a released tunnelled codec discards its peeked picture.
            finite?.keepQueuedPreview()
            return super.shouldReinitCodec()
        }
        // The next feed invocation follows successful submission of onQueueInputBuffer's sample.
        return finite?.drainAndRewind() == true || super.shouldReinitCodec()
    }

    override fun renderOutputBufferV21(
        codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long,
    ) {
        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
        // Use the released frame's period coordinate, not a possibly stale Player position.
        (stream as? FinitePreviewSampleStream)?.frameRendered(presentationTimeUs, PausedSeekConfirmation.FRAME_RENDERED)
    }

    override fun onProcessedTunneledBuffer(presentationTimeUs: Long) {
        // Tunnelled frames report the codec timestamp, which includes the stream offset. Read the offset
        // first: the last frame of a stream lets super apply the next stream's offset.
        val offsetUs = outputStreamOffsetUs
        super.onProcessedTunneledBuffer(presentationTimeUs)
        (stream as? FinitePreviewSampleStream)?.frameRendered(
            presentationTimeUs - offsetUs, PausedSeekConfirmation.FRAME_RENDERED,
        )
    }

    override fun onCodecInitialized(
        name: String, codecConfiguration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long, initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, codecConfiguration, initializedTimestampMs, initializationDurationMs)
        enableTunnelPeek()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        enableTunnelPeek()
    }

    /** Media3 enables peek from API 33; Android 12 leaves an unset peek to the device. */
    private fun enableTunnelPeek() {
        if (sdkInt !in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 ||
            !configuration.tunneling
        ) {
            return
        }
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_TUNNEL_PEEK, 1) })
    }

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
}

internal interface FinitePreviewSampleStream {
    fun outputAllowed(): Boolean
    fun inputQueued()
    fun drainAndRewind(): Boolean

    /** Lets a tunnelled decoder keep a queued finite preview as decoding state, without a rewind. */
    fun keepQueuedPreview()

    /**
     * Reports a video frame at its period time [presentationTimeUs]: released for display or rendered
     * by the tunnel ([PausedSeekConfirmation.FRAME_RENDERED]), or, for a paused tunnelled decoder from
     * API 31, a key frame handed to the codec with peek requested
     * ([PausedSeekConfirmation.KEY_FRAME_QUEUED]).
     */
    fun frameRendered(presentationTimeUs: Long, confirmation: PausedSeekConfirmation)

    /** Reports whether the video renderer reading this stream is started, that is, playing. */
    fun rendererStarted(started: Boolean)
}
