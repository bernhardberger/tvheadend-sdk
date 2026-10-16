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
namespace is `v2-` followed by the first 32 hex characters of SHA-256 over a
kotlinx-serialization protobuf list of host, decimal port and username. Field
boundaries are unambiguous even with colons or embedded separators. Anonymous profiles use an empty
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

A different profile gets a different namespace. Legacy unversioned namespaces
are not read or migrated: their delimiter-based identity cannot safely identify
the original profile. Recognized legacy directories, including metadata and
artwork, are deleted on restore instead of leaving unreachable files indefinitely.
No unrelated data under the supplied application root is deleted.

## Serialisation

`AGENTS.md` forbids hand-written serialisers. Use `kotlinx-serialization`
(`org.jetbrains.kotlinx:kotlinx-serialization-core` + `-protobuf`) with
internal `@Serializable` DTOs in `sdk-core/.../cache/`. The public models
(`Channel`, `ChannelTag`, `ChannelService`, `EpgEvent`, `EpgCoverage`,
`EpgRating`, `EpgEpisode`) keep their private constructors and redacted
`toString()`; DTO mapping is one internal function per type, in both
directions, covered by a round-trip test. Catalog and artwork envelopes carry a
schema version. The EPG file starts with the versioned `EPG4` magic and a timestamp,
followed by receipt times, coverage and per-channel query times as length-prefixed
protobuf records using standard data streams. Events follow, ordered by start time
then event ID; each has a whole-second start prefix before its protobuf record. Bump
the magic for incompatible framing or DTO changes; an unrecognized format is
discarded and rebuilt from server metadata.

Restore captures the raw device clock at whole-second precision. It stops before
reading or decoding the first event payload starting beyond now + 24 hours, and
omits decoded events ending before now − 6 hours. Coverage is intersected with
that window and `queriedTo` is capped at the future cut, so `knownTo` cannot claim
the full persisted guide. Receipt timestamps are preserved, but the live sync
marker clears seeded query timestamps and establishes scheduling proof only from
live metadata. Persistence still writes the complete live snapshot. Offline
browsing uses the same near window; no second far-guide restore is scheduled.
The coverage intersection preserves a subset of the previously proven live
claim, including known-empty gaps; it is not recomputed from restored events.
A channel bracketed by expired and far-future events can therefore retain the
intersected non-empty coverage interval with no visible events. It remains
display-only and cannot authorize a live coverage acquisition.
Restored stale event lists are ordered by start time then ID, regardless of their
original live insertion order. Equality checks on round-tripped fixtures must
use that order.

Skipped tail bytes are never read or decoded in that restore, so corruption there
goes undetected unless a later restore reaches them. Corruption detected in the
decoded prefix remains a nonfatal cache miss. A badly wrong device clock (for
example before NTP synchronization), or a corrupted start prefix that appears
beyond the cut, can shorten or empty the restored guide without deleting its file.
`EPG3` is discarded once on
upgrade without affecting catalog or artwork files. Deleted historical events
remain unpersisted as before.

Explain the library choice in the commit body as the guide requires.
Room and DataStore are not used for this JVM-only cache. Per-record protobuf
encoding avoids holding a whole-guide DTO graph and encoded buffer alongside
the live snapshot.

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

On `connect(profile)`, the owner restores that profile's namespace in a structured
child alongside connecting and authentication. A slow load never delays starting
the connection. A retained in-memory snapshot skips disk restore on reconnect.
Within `metadataRetention`, a restore that wins before generation binding seeds
`ChannelRepositoryState.Stale` and `EpgRepositoryState.Stale`; binding then publishes
`Synchronizing` with those snapshots. If authentication wins, restore can still
seed retained-only snapshots for that exact synchronizing generation. Restore
after the initial-sync marker is discarded.

The catalog and guide are accepted independently: catalog loading and acceptance
finish before guide decoding begins. The later guide seed preserves the catalog
and its receipt times, and each acceptance rechecks the same restore fences.
A missing or failed guide load does not suppress the cached catalog.

The commit checks the session token, namespace and atomic cache-clear epoch under
the owner/metadata locks. A bound generation also requires the captured attempt
revision, so an earlier attempt cannot seed a synchronizing retry. While unbound,
even after a failed connection, the same profile can accept display-only stale data
for offline browsing; the next bind carries it into `Synchronizing`.
Teardown cancels and joins the restore child before stopping the writer or replacing
the profile. Persistence starts once for the captured namespace before restoring,
so a completed live sync can still be flushed if teardown cancels a slow restore.
Seeding is independent of persistence, including when discarded or restore fails.
EPG indexes are prepared before acquiring owner/metadata locks. Restore acceptance
holds no cache locks, and cache clear invalidates pending acceptance atomically.
Clear leaves in-memory session state untouched: a restore racing clear may still
display a pre-clear snapshot, including when clear finishes after its epoch check
or a load overtakes deletion. These operations linearize as restore before clear.
The seed is display-only, never enters persistence, and later live publications
replace it. A cancelled clear may still invalidate an in-flight display seed even
if deletion does not finish. A late restore can also be discarded during the
transition between retiring a failed generation and resetting its working state.

Cached data is display-only: it never enters working reducers or supplies live EPG
coverage proof. Original receipt times and per-channel query timestamps are retained
as before; the initial-sync marker clears the cached query timestamps and establishes
fresh scheduling proof from live sync. HTSP re-sends the configured horizon, so the
cached guide does not reduce network traffic. Authority stays `SYNCHRONIZING`/`STALE`
until initial sync completes; the SDK never claims `CURRENT` from disk.

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
