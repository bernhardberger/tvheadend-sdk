@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 30_000
private const val BUFFER_FOR_PLAYBACK_MS = 2_000
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500

/**
 * Creates the recommended [LoadControl] for players that host TVHeadend sources.
 *
 * The values are tuned together with the [PlaybackRecoveryPolicy] defaults:
 *
 * - A live TVHeadend subscription is pushed at 1× real time and cannot be pulled ahead, so the
 *   2 s buffer required to start playback is visible tuning delay. It absorbs start-up jitter
 *   where 1 s stalled on hardware.
 * - After a rebuffer, Media3's 5 s default is spent waiting for real time on a live target and
 *   pushes it into [PlaybackRecoveryPolicy] stuck-buffering recovery (6 s by default), so 1.5 s
 *   is required instead.
 * - The 15 s minimum and 30 s maximum buffer apply only to the pull-based recording path. They
 *   are not backpressure and do not bound what a live subscription pushes.
 */
@androidx.media3.common.util.UnstableApi
public fun createTvheadendLoadControl(): LoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()
