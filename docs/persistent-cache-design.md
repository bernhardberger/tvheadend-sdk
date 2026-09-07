# Persistent metadata and artwork cache

Status: slices 1 and 2 implemented. Target release: 0.8.0 (new public API).
Consumer plan: `tvheadend-player/docs/persistent-cache-plan.md`.

## Problem

Every process start begins with nothing. The consumer shows "Loading channel
information…" until the HTSP initial sync completes, every picon is fetched
again over `fileOpen`/`fileRead`/`fileClose`, and after a reconnect the only
retained data is whatever the previous process instance held in memory.
Artwork keys are process-local (`TvheadendArtworkMemoryKeys.processNamespace`
in `sdk-android/.../TvheadendArtwork.kt:94-107`), so even Coil's memory cache
cannot survive a restart.

## Ownership

The SDK owns the mechanics: where bytes live inside a supplied root, the
restart-stable namespace, serialisation, atomic writes, corruption handling,
eviction, cold-start publication, and the artwork fetch path. The consumer
supplies policy only: an opt-in root directory, retention, a size budget, and
a clear action. The consumer never sees a file, a key, or a namespace.

## Public API (sdk-core unless stated)

```kotlin
public class MetadataCachePolicy private constructor(
    internal val root: java.io.File,
    public val metadataRetention: Duration,   // catalog + EPG, default 7.days
    public val artworkRetention: Duration,    // default 30.days
    public val artworkMaxBytes: Long,         // default 64 MiB
) {
    public companion object {
        public fun create(root: File, metadataRetention: Duration = 7.days,
            artworkRetention: Duration = 30.days, artworkMaxBytes: Long = 64L shl 20): MetadataCachePolicy
    }
}

public fun createTvheadendSession(
    epgCoveragePolicy: EpgCoveragePolicy,
    cachePolicy: MetadataCachePolicy,
): TvheadendSession

public interface TvheadendSession {
    // existing members unchanged
    public val cache: SessionCache
}

public interface SessionCache {
    public val statistics: StateFlow<CacheStatistics>   // bytes, entry counts, lastWrite; no paths
    public suspend fun clear()                          // all namespaces under the root
}
```

`init` validation follows `EpgCoveragePolicy` (`EpgRepository.kt:262-312`):
positive durations, positive byte budget. The two existing
`createTvheadendSession` overloads keep their behaviour: no policy means no
persistence. `SessionRegistry.acquire` (`TvheadendSession.kt:129-205`) applies
the policy only when it creates a fresh owner, exactly like the EPG policy.

Android consumers pass `context.cacheDir` or `context.filesDir`; sdk-core stays
pure JVM and never touches `Context`.

## Namespace

Cache identity is the same predicate the session already uses to decide whether
retained metadata survives a reconnect: `ServerProfile.hasSameConfigurationAs`
(`TvheadendSession.kt:234-237`, host + port + credentials). The on-disk
namespace is `SHA-256(host ':' port ':' username)` rendered as 32 hex characters,
computed inside sdk-core next to that predicate. Anonymous profiles use an empty
username. The password never participates. The namespace, host, and username
must never appear in logs, diagnostics, statistics, or `toString()`
(`AGENTS.md`, cache keys and paths are sensitive).

Layout under the supplied root:

```
tvheadend-sdk/
  <namespace>/
    catalog.bin      channel catalog + tags
    epg.bin          EPG snapshot
    artwork/
      index.bin      schema version, id -> size, lastAccess, storedAt, SHA-256 digest
      <id>           raw bytes as served by imagecache
```

A different profile gets a different namespace; old namespaces age out by
retention or are removed by `clear()`.

## Serialisation

`AGENTS.md` forbids hand-written serialisers. Use `kotlinx-serialization`
(`org.jetbrains.kotlinx:kotlinx-serialization-core` + `-protobuf`) with
internal `@Serializable` DTOs in `sdk-core/.../cache/`. The public models
(`Channel`, `ChannelTag`, `ChannelService`, `EpgEvent`, `EpgCoverage`,
`EpgRating`, `EpgEpisode`) keep their private constructors and redacted
`toString()`; DTO mapping is one internal function per type, in both
directions, covered by a round-trip test. Every file starts with a schema
version; a mismatch discards the file silently.

Explain the library choice in the commit body as the guide requires.
Room and DataStore are rejected: sdk-core is JVM-only and neither suits a
100k-event snapshot written as one unit.

DVR entries are not cached. They carry `owner`, `creator`, and `path`
(`DvrRepository.kt:98-138`), change quickly, and are small to resync.

## Writes

A `MetadataCacheWriter` is a `ConnectionOwner` child with its own
`SupervisorJob` scope on an injected IO dispatcher, mirroring
`PlaybackSessionChildren` (`session/SessionSubscriptions.kt:386-448`). It is fed
from the single publication choke point `PhaseOneSessionMetadata.publishCurrent`
(`session/SessionResources.kt:1005-1018`) and `publishCurrentEpg` (`:1020-1024`)
through a conflated channel; publication itself stays synchronous under the
metadata lock and never blocks on IO.

- Catalog: write on every change (small).
- EPG: coalesce; write at most once per 60 s while changes keep arriving, and
  always on `Ready -> Disconnected`, `disconnect()`, and `shutdown()` under
  `NonCancellable` in the ordered cleanup list (`ConnectionOwner.kt:342-352`).
- Every write goes to `<file>.tmp` then `Files.move(ATOMIC_MOVE)`.
- IO failures are logged as bounded diagnostics without paths and never fail the
  session. `CancellationException` propagates.

## Cold start and reconnect

On `connect(profile)`, before the gateway connects, the owner loads the
namespace for that profile. If `catalog.bin` is within `metadataRetention` it
seeds the published catalog as `ChannelRepositoryState.Stale`, and
`bindGeneration` (`SessionResources.kt`) then publishes
`Synchronizing(previousCatalog)`, so the consumer can render a channel list
immediately. The EPG snapshot is seeded the same way as `EpgRepositoryState.Stale`
for cold-start display only: `bindGeneration` clears the EPG reducer and the
HTSP initial sync re-sends the configured horizon, so the cached guide does not
reduce network traffic. Authority stays `SYNCHRONIZING`/`STALE` until the HTSP
initial sync completes; the SDK never claims `CURRENT` from disk.

Files older than retention are deleted at load. Corrupt or truncated files are
deleted and the session proceeds as today.

## Artwork

`ArtworkLoader.loadArtwork` (`Artwork.kt:65-71`) gains a store in front of the
gateway: hit within `artworkRetention` returns bytes from disk and touches
`lastAccess`; miss fetches through the existing three-handle semaphore
(`HtspProtocolGateway.kt:214`) and stores the bytes. Eviction is LRU by bytes
against `artworkMaxBytes` across all namespaces, run after each store on the IO
dispatcher under the runtime store mutex. Restore also prunes expired entries.
`ArtworkFailure.FILE_UNAVAILABLE` from the server removes any stale entry.

sdk-android `TvheadendArtwork` changes:

- `TvheadendArtworkKeyer` returns an opaque UUID digest of
  `tvheadend-artwork:<namespace>:<id>` when a cache policy is active, otherwise
  the current process-local key. The `isCurrent` gate moves from the keyer
  (`:85-92`) into the fetcher so a stale observation causes a fetch failure, not
  a key change. The model captures `ArtworkLoader.cacheKey` while current, so
  an old model never acquires a replacement profile's key. The namespace itself
  does not cross the core boundary. Coil memory hits can reuse decoded images;
  policy retention and clear apply to SDK disk bytes, not Coil memory.
- The fetcher keeps returning a stream `SourceFetchResult` with no Coil disk
  cache key. Persistence is the SDK store, not Coil's disk cache; consumers
  should not configure a Coil disk cache for artwork.
- Update the KDoc at `:67-74` that currently states persistent caching is not
  authorised, and the `sdk-android` changelog.

## Clear

`SessionCache.clear()` deletes every namespace under the root and resets
statistics. While connected the in-memory state is untouched; the next write
recreates the files. It is safe to call from any thread; it runs on the writer
IO dispatcher under the store mutex and returns after deletion. An epoch fences
artwork fetches already pending at deletion from repopulating cleared files.

## Tests (all JVM, temp directories)

- Round trip of each DTO with every nullable field set and unset.
- Cold start publishes `Stale` then `Synchronizing(previousCatalog)` from disk;
  expired files are ignored and deleted; corrupt or invalid files are deleted
  and ignored.
- Writer coalescing: N publications inside 60 s produce one EPG write; the
  disconnect flush writes the latest snapshot.
- Namespace: same host/port/username maps to the same directory; a password
  change does not change it; hex only, no raw host in any name.
- Artwork: hit avoids the gateway, miss stores, LRU evicts oldest access first,
  `FILE_UNAVAILABLE` removes the entry, `clear()` empties everything.
- Konsist: nothing under `cache/` is public except `MetadataCachePolicy`,
  `SessionCache`, `CacheStatistics`.

Gates: `./gradlew --no-daemon build check`, ABI dump update for `sdk-core`,
CHANGELOG `## [0.8.0]` paragraph, `docs/consumer-guide.md` section on the policy.

## Slices for delegation

1. `sdk-core`: namespace, policy, DTOs, catalog + EPG store, writer, cold-start
   publication, `clear()`, statistics.
2. `sdk-core` + `sdk-android`: artwork store and the Coil keyer/fetcher change.
3. Release 0.8.0.

Each slice is independently testable and reviewable; slice 2 may not start
until slice 1 is merged because it shares the namespace and writer scope.

## Out of scope

DVR persistence, cross-server artwork sharing, HTTP artwork, encryption at rest
(the root is app-private storage; the consumer chooses `cacheDir` vs
`filesDir`), and any consumer-side cache mechanics.
