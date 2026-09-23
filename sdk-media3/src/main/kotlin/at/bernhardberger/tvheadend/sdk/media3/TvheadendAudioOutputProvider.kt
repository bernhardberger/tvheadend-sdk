@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Platform audio output with optional encoded passthrough and qualified device workarounds.
 *
 * Use one instance per player, supplying it to [createTvheadendRenderersFactory]. Disabling
 * passthrough makes compressed formats use a decoder; PCM output retains platform capabilities.
 * Startup configuration freezes once format support has been queried. An application loading preferences
 * asynchronously may call [configurePassthrough] before preparing the first source. Applying a
 * different mode after that uses [setPassthroughEnabled], which restarts only the audio renderer.
 */
public class TvheadendAudioOutputProvider internal constructor(
    delegate: AudioOutputProvider,
    passthroughEnabled: Boolean,
) : ForwardingAudioOutputProvider(delegate) {
    public constructor(context: Context, passthroughEnabled: Boolean = true) :
        this(platformAudioOutputProvider(context), passthroughEnabled)

    private var passthroughEnabled = passthroughEnabled
    private var formatSupportQueried = false
    private val changes = Mutex()

    public val isPassthroughEnabled: Boolean
        @Synchronized get() = passthroughEnabled

    /**
     * Changes output without replacing the source, seeking, or changing play intent.
     *
     * Call on the application looper of the player using this provider. Keep that player alive
     * until completion/cancellation. Returns false if audio could not be quiesced within two seconds;
     * the caller may retry. Audio overrides are cleared before re-enabling: callers may restore a
     * remembered choice after onTracksChanged reports support in the new mode. Unrelated parameters
     * are retained.
     */
    public suspend fun setPassthroughEnabled(player: ExoPlayer, enabled: Boolean): Boolean = changes.withLock {
        check(Looper.myLooper() === player.applicationLooper) { "Audio output changes require the player application looper" }
        if (isPassthroughEnabled == enabled) return@withLock true
        val renderers = (0 until player.rendererCount).flatMap { index ->
            listOfNotNull(player.getRenderer(index), player.getSecondaryRenderer(index))
        }.filter { it.trackType == C.TRACK_TYPE_AUDIO }
        val alreadyDisabled = C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes
        try {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine { continuation ->
                    val commitLock = Any()
                    var cancelled = false
                    // setParameters posts TRACK_SELECTION_INVALIDATED before this message. That
                    // handler completes deselection synchronously, including renderer disable.
                    val message = player.createMessage { _, _ ->
                        if (!continuation.isActive) return@createMessage
                        val applied = try {
                            if (renderers.any { it.state != Renderer.STATE_DISABLED }) {
                                false // A newer track choice/recovery re-enabled audio; do not reset it.
                            } else {
                                // Disable alone can retain a decoder. Reset releases it so switching
                                // PCM -> passthrough also reconstructs the codec/bypass decision.
                                renderers.forEach { it.reset() }
                                synchronized(commitLock) {
                                    if (cancelled || !continuation.isActive) false else {
                                        synchronized(this@TvheadendAudioOutputProvider) { passthroughEnabled = enabled }
                                        true
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            false
                        }
                        continuation.resume(applied)
                    }.send()
                    continuation.invokeOnCancellation {
                        synchronized(commitLock) { cancelled = true }
                        message.cancel()
                    }
                }
            }
        } finally {
            // An override can bypass support checks in DefaultTrackSelector. Do not enable a
            // formerly supported passthrough-only track when PCM has no decoder for it. Also clear
            // choices made during the wait; applications retain intent outside these parameters.
            val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            if (!alreadyDisabled) parameters.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            player.trackSelectionParameters = parameters.build()
        }
        isPassthroughEnabled == enabled
    }

    /** Sets the mode before the first source is prepared. Cannot change an in-use provider's mode. */
    @Synchronized
    public fun configurePassthrough(enabled: Boolean) {
        check(!formatSupportQueried || passthroughEnabled == enabled) {
            "Passthrough must be configured before playback"
        }
        passthroughEnabled = enabled
    }

    @Synchronized
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        formatSupportQueried = true
        return if (!passthroughEnabled && formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            AudioOutputProvider.FormatSupport.UNSUPPORTED
        } else {
            super.getFormatSupport(formatConfig)
        }
    }
}

private fun platformAudioOutputProvider(context: Context): AudioOutputProvider =
    AudioTrackAudioOutputProvider.Builder(context)
        .setAudioTrackBuilderModifier { builder, config ->
            if (requiresFreshAc3AudioSession(Build.MANUFACTURER, Build.DEVICE, Build.PRODUCT, Build.VERSION.SDK_INT, config)) {
                // On this platform a replacement direct AC-3 track can inherit the old playback
                // head even after flush(). A fresh session starts at zero without decoding to PCM.
                // Related platform report: https://github.com/androidx/media/issues/3269.
                val sessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
                if (sessionId > 0) builder.setSessionId(sessionId)
            }
        }
        .build()

internal fun requiresFreshAc3AudioSession(
    manufacturer: String,
    device: String,
    product: String,
    sdkInt: Int,
    config: AudioOutputProvider.OutputConfig,
): Boolean =
    manufacturer.equals("TCL", ignoreCase = true) && device == "G10" && product == "G10_4K_GB" && sdkInt == 31 &&
        config.encoding == C.ENCODING_AC3 && !config.isOffload && !config.isTunneling
