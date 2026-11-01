@file:OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)

package at.bernhardberger.tvheadend.sdk.consumer

import at.bernhardberger.tvheadend.sdk.android.DiscoveredTvheadendServer
import at.bernhardberger.tvheadend.sdk.android.TvheadendDiscoveryState
import at.bernhardberger.tvheadend.sdk.android.TvheadendDiscoveryTestFactory

public fun stagedDiscoveryTestState(
    servers: List<DiscoveredTvheadendServer>,
): TvheadendDiscoveryState.Discovering = TvheadendDiscoveryTestFactory.discovering(servers)

public fun stagedEmptyDiscoveryTestState(): TvheadendDiscoveryState.Discovering =
    TvheadendDiscoveryTestFactory.discovering()
