@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class TvheadendAudioOutputProviderTest {
    @Test
    fun `passthrough retains platform support while disabled mode only permits PCM`() {
        val delegate = FakeAudioOutputProvider()
        val provider = TvheadendAudioOutputProvider(delegate, true)
        val pcm = format(MimeTypes.AUDIO_RAW)
        val compressed = listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_AAC)
        for (mime in compressed) assertSame(delegate.support, provider.getFormatSupport(format(mime)))
        val decoded = TvheadendAudioOutputProvider(delegate, false)
        for (mime in compressed) {
            val support = decoded.getFormatSupport(format(mime))
            assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, support.supportLevel)
            assertFalse(support.isFormatSupportedForOffload)
        }
        assertSame(delegate.support, decoded.getFormatSupport(pcm))
        assertSame(delegate.support, provider.getFormatSupport(format(MimeTypes.AUDIO_AC3)))
        assertSame(delegate.config, provider.getOutputConfig(pcm))
    }

    @Test
    fun `startup configuration freezes at first query without synthetic capability notifications`() {
        val delegate = FakeAudioOutputProvider()
        val provider = TvheadendAudioOutputProvider(delegate, true)
        val supportSeen = mutableListOf<Int>()
        val listener = AudioOutputProvider.Listener {
            supportSeen += provider.getFormatSupport(format(MimeTypes.AUDIO_AC3)).supportLevel
        }
        provider.addListener(listener)
        provider.configurePassthrough(false)
        assertTrue(supportSeen.isEmpty())
        assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, provider.getFormatSupport(format(MimeTypes.AUDIO_AC3)).supportLevel)
        provider.configurePassthrough(false)
        assertThrows(IllegalStateException::class.java) { provider.configurePassthrough(true) }
        assertTrue(supportSeen.isEmpty())
        // Real route/capability changes still reach the platform listener unchanged.
        delegate.listeners.single().onFormatSupportChanged()
        assertEquals(listOf(AudioOutputProvider.FORMAT_UNSUPPORTED), supportSeen)
        provider.removeListener(listener)
        assertTrue(delegate.listeners.isEmpty())
        provider.addListener(listener)
        provider.release()
        assertTrue(delegate.released)
        assertTrue(delegate.listeners.isEmpty())
    }

    @Test
    fun `fresh session workaround only affects qualified direct AC3 outputs`() {
        val ac3 = AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_AC3).build()
        assertTrue(requiresFreshAc3AudioSession("TCL", "G10", "G10_4K_GB", 31, ac3))
        assertTrue(requiresFreshAc3AudioSession("tcl", "G10", "G10_4K_GB", 31, ac3))
        assertFalse(requiresFreshAc3AudioSession("Other", "G10", "G10_4K_GB", 31, ac3))
        assertFalse(requiresFreshAc3AudioSession("TCL", "G08", "G10_4K_GB", 31, ac3))
        assertFalse(requiresFreshAc3AudioSession("TCL", "G10", "G08_4K_GB", 31, ac3))
        for (sdk in listOf(30, 32, 34)) assertFalse(requiresFreshAc3AudioSession("TCL", "G10", "G10_4K_GB", sdk, ac3))
        val excluded = listOf(
            AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_AC3).setIsOffload(true).build(),
            AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_AC3).setIsTunneling(true).build(),
        ) + listOf(C.ENCODING_PCM_16BIT, C.ENCODING_E_AC3, C.ENCODING_DTS).map {
            AudioOutputProvider.OutputConfig.Builder().setEncoding(it).build()
        }
        for (config in excluded) assertFalse(requiresFreshAc3AudioSession("TCL", "G10", "G10_4K_GB", 31, config))
    }

    private fun format(mime: String) = AudioOutputProvider.FormatConfig.Builder(
        Format.Builder().setSampleMimeType(mime).setSampleRate(48_000).setChannelCount(2).build(),
    ).build()
}

private class FakeAudioOutputProvider : AudioOutputProvider {
    val support = AudioOutputProvider.FormatSupport.Builder()
        .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
        .setIsFormatSupportedForOffload(true).build()
    val config = AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_PCM_16BIT).build()
    val listeners = mutableSetOf<AudioOutputProvider.Listener>()
    var released = false
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) = support
    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig) = config
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = error("No output needed")
    override fun addListener(listener: AudioOutputProvider.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioOutputProvider.Listener) { listeners.remove(listener) }
    override fun release() { released = true; listeners.clear() }
}
