package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MediaItem

/** Reserved media id of every item installed by the TVHeadend live source. */
internal const val LIVE_MEDIA_ID: String = "tvheadend-live"

/**
 * Returns whether this item carries the media id the SDK reserves for its live source.
 *
 * The SDK's live source sets that id on its items, including during timeshift, and SDK recording
 * items, including recordings that are still growing, never carry it. The check compares only the
 * media id, so apps must not use that id for their own items.
 */
public fun MediaItem.isTvheadendLive(): Boolean = mediaId == LIVE_MEDIA_ID
