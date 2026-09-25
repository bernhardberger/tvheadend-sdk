package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MediaItem

/** Media id of every item installed by the TVHeadend live source. */
internal const val LIVE_MEDIA_ID: String = "tvheadend-live"

/**
 * Returns whether this item belongs to a TVHeadend live source installed by the SDK.
 *
 * True for the live source's items, including timeshift on the same live source. False for
 * TVHeadend recordings, including recordings that are still growing, and for foreign items.
 */
public fun MediaItem.isTvheadendLive(): Boolean = mediaId == LIVE_MEDIA_ID
