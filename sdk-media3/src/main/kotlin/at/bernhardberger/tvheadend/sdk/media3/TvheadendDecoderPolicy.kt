@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory

/**
 * Creates renderers that prefer platform codecs and use the bundled FFmpeg audio decoder only
 * when no platform renderer supports the format.
 */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendRenderersFactory(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
