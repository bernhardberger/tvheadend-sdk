@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.content.ContextWrapper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

internal class TvheadendVideoRendererTest {
    @Test
    fun `tunnelled render signal reaches the live stream at its period coordinate`() {
        val stream = RecordingPreviewStream()
        val renderer = enabledRenderer(stream, tunneling = true)

        renderer.callProtected("onProcessedTunneledBuffer", STREAM_OFFSET_US + 250_000L)

        assertEquals(listOf(250_000L), stream.rendered)
    }

    @Test
    fun `tunnelled last frame of a stream keeps its own stream offset`() {
        val stream = RecordingPreviewStream()
        val renderer = enabledRenderer(stream, tunneling = true)
        val lastQueuedUs = STREAM_OFFSET_US + 250_000L
        // Media3 sets this when the codec accepts input, which needs a platform codec on the JVM.
        MediaCodecRenderer::class.java.getDeclaredField("largestQueuedPresentationTimeUs").apply {
            isAccessible = true
            setLong(renderer, lastQueuedUs)
        }
        renderer.replaceStream(
            arrayOf(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
            stream,
            0L,
            NEXT_STREAM_OFFSET_US,
            MediaSource.MediaPeriodId(Any()),
        )

        renderer.callProtected("onProcessedTunneledBuffer", lastQueuedUs)

        assertEquals(listOf(250_000L), stream.rendered)
        assertEquals(NEXT_STREAM_OFFSET_US, renderer.callProtected("getOutputStreamOffsetUs"))
    }

    @Test
    fun `tunnelled finite preview stays queued instead of draining the codec`() {
        val stream = RecordingPreviewStream(drains = true)
        val renderer = enabledRenderer(stream, tunneling = true)

        assertFalse(renderer.callProtected("shouldReinitCodec") as Boolean)

        assertEquals(0, stream.drainCalls)
        assertEquals(1, stream.keepCalls)
    }

    @Test
    fun `untunnelled finite preview still drains and rewinds`() {
        val stream = RecordingPreviewStream(drains = true)
        val renderer = enabledRenderer(stream, tunneling = false)

        assertEquals(true, renderer.callProtected("shouldReinitCodec"))

        assertEquals(1, stream.drainCalls)
        assertEquals(0, stream.keepCalls)
        assertEquals(emptyList<Long>(), stream.rendered)
    }

    private fun enabledRenderer(stream: SampleStream, tunneling: Boolean): TvheadendVideoRenderer {
        // The early-time forecaster needs android.util.Range, which the JVM stubs leave empty.
        val renderer = TvheadendVideoRenderer(
            MediaCodecVideoRenderer.Builder(ApplicationContext()).experimentalSetLateThresholdToDropDecoderInputUs(C.TIME_UNSET),
        )
        renderer.init(0, PlayerId.UNSET, Clock.DEFAULT)
        if (tunneling) renderer.handleMessage(Renderer.MSG_SET_AUDIO_SESSION_ID, AUDIO_SESSION_ID)
        renderer.enable(
            RendererConfiguration(tunneling),
            arrayOf(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
            stream,
            0L,
            false,
            true,
            0L,
            STREAM_OFFSET_US,
            MediaSource.MediaPeriodId(Any()),
        )
        return renderer
    }

    private fun TvheadendVideoRenderer.callProtected(name: String, vararg args: Long): Any? {
        val method = generateSequence<Class<*>>(javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(this, *args.toTypedArray())
    }

    /** Android's JVM stubs answer null, but Media3 retains the application context. */
    private class ApplicationContext : ContextWrapper(null) {
        override fun getApplicationContext() = this
    }

    private class RecordingPreviewStream(private val drains: Boolean = false) : SampleStream, FinitePreviewSampleStream {
        val rendered = mutableListOf<Long>()
        var drainCalls = 0
        var keepCalls = 0

        override fun outputAllowed(): Boolean = true
        override fun inputQueued(): Unit = Unit
        override fun drainAndRewind(): Boolean {
            drainCalls++
            return drains
        }
        override fun keepQueuedPreview() {
            keepCalls++
        }
        override fun frameRendered(presentationTimeUs: Long) {
            rendered += presentationTimeUs
        }
        override fun isReady(): Boolean = false
        override fun maybeThrowError(): Unit = Unit
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            C.RESULT_NOTHING_READ
        override fun skipData(positionUs: Long): Int = 0
    }

    private companion object {
        const val STREAM_OFFSET_US = 1_000_000_000_000L
        const val NEXT_STREAM_OFFSET_US = 2_000_000_000_000L
        const val AUDIO_SESSION_ID = 1
    }
}
