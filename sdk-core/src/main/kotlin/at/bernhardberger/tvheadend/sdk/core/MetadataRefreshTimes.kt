package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import kotlin.time.Instant

/**
 * Local wall-clock acceptance times, not broadcaster acquisition times. Null means unknown.
 * Clock corrections may move these times backwards; display ages should clamp at zero.
 * These values never authorize generation-bound actions.
 */
@ExperimentalTvheadendApi
public data class MetadataRefreshTimes(
    public val lastSynchronizedAt: Instant? = null,
    public val lastUpdatedAt: Instant? = null,
)
