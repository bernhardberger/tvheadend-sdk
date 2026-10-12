@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import kotlin.time.Instant

/**
 * Restart-stable, opaque identity of one server profile's cache namespace.
 *
 * The value is a versioned lowercase hex digest and contains no server identity in clear text. It must
 * never appear in logs, diagnostics, or error messages.
 */
@JvmInline
internal value class CacheNamespace(val value: String) {
    init {
        require(value.startsWith("v2-") && value.length == NAMESPACE_LENGTH &&
            value.drop(3).all { it in '0'..'9' || it in 'a'..'f' }) {
            "Cache namespace must be a versioned lowercase hex digest"
        }
    }

    companion object {
        const val NAMESPACE_LENGTH: Int = 35
    }
}

/** Content and its original local receipt times; disk-write time remains separate. */
internal data class CachedCatalog(
    val catalog: ChannelCatalog,
    val refreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
) {
    override fun toString(): String = "CachedCatalog(<redacted>)"
}

internal data class CachedEpg(
    val snapshot: EpgSnapshot,
    val refreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
    val lastQueriedAt: Map<ChannelId, Instant> = emptyMap(),
) {
    override fun toString(): String = "CachedEpg(<redacted>)"
}

/**
 * Persistent store for one policy root.
 *
 * Every call may perform blocking file IO and must be invoked on an IO dispatcher by the caller.
 * Implementations never throw for missing, expired, or corrupt data: they return `null` and delete
 * the offending file. IO failures on write are swallowed after best-effort cleanup; the session
 * must not fail because the cache did. `CancellationException` always propagates.
 */
internal interface MetadataCacheStore {
    /** Returns the cached catalog for [namespace] stored no earlier than [notBefore], else `null`. */
    suspend fun loadCatalog(namespace: CacheNamespace, notBefore: Instant): CachedCatalog?

    /** Returns the cached EPG snapshot for [namespace] stored no earlier than [notBefore], else `null`. */
    suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): CachedEpg?

    /** Atomically replaces the cached catalog for [namespace], recording [storedAt]. */
    suspend fun storeCatalog(namespace: CacheNamespace, catalog: CachedCatalog, storedAt: Instant)

    /** Atomically replaces the cached EPG snapshot for [namespace], recording [storedAt]. */
    suspend fun storeEpg(namespace: CacheNamespace, snapshot: CachedEpg, storedAt: Instant)

    /** Deletes every namespace under the root. */
    suspend fun clear()

    /** Measures current usage by walking the root. */
    suspend fun statistics(): CacheStatistics
}
