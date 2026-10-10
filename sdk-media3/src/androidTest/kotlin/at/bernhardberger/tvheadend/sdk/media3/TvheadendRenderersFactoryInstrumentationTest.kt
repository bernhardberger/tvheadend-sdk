@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TvheadendRenderersFactoryInstrumentationTest {
    @Test
    fun application_supplied_ffmpeg_audio_renderer_follows_platform_audio_renderer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderers = createTvheadendRenderersFactory(context).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            TextOutput { },
            MetadataOutput { },
        )
        try {
            val platformAudio = renderers.indexOfFirst { it is MediaCodecAudioRenderer }
            val extensionAudio = renderers.indexOfFirst { it is FfmpegAudioRenderer }
            assertTrue("platform audio renderer missing", platformAudio >= 0)
            assertTrue("application FFmpeg audio renderer missing", extensionAudio >= 0)
            assertTrue("FFmpeg audio renderer precedes the platform renderer", extensionAudio > platformAudio)
        } finally {
            renderers.forEach { it.release() }
        }
    }
}
