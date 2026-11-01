package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi
import java.util.Collections

/**
 * Creates discovery snapshots for application tests.
 *
 * These snapshots do not run Android discovery or prove endpoint reachability.
 */
@TvheadendTestingApi
public object TvheadendDiscoveryTestFactory {
    /**
     * Creates a test-only [TvheadendDiscoveryState.Discovering] snapshot.
     *
     * Copies [servers] into an immutable list, preserving order and content. No NSD or probes run;
     * the snapshot does not prove real discovery or endpoint reachability.
     *
     * @param servers endpoints to copy into the test snapshot
     */
    @JvmStatic
    public fun discovering(
        servers: List<DiscoveredTvheadendServer> = emptyList(),
    ): TvheadendDiscoveryState.Discovering =
        TvheadendDiscoveryState.Discovering(Collections.unmodifiableList(ArrayList(servers)))
}
