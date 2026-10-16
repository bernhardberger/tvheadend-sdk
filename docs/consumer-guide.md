# Consumer API guide

This guide covers the application-facing API of the independently maintained
Tvheadend Kotlin SDK. It is not official Tvheadend software. The SDK is GPLv3
and links the GPLv3 HTSP protocol library.

## Mutable live startup buffer (experimental)

Opt into `ExperimentalTvheadendApi` to use `TvheadendLiveStartBuffer` and the
SDK-owned `TvheadendLoadControl` handle. These types need no Media3 unstable
opt-in. Pass an application-created `ExoPlayer.Builder` to `handle.configure`;
the application still builds and releases its Player. Use one handle per Player.

```kotlin
val buffer = TvheadendLiveStartBuffer(500.milliseconds)
val handle = createTvheadendLoadControl(buffer)
val player = handle.configure(ExoPlayer.Builder(context)).build()
val coordinator = createTvheadendPlaybackCoordinator(player, handle)
buffer.duration = learnedDuration
```

The default is 2 seconds; 500 ms is an explicit application policy. Settings
and learning remain application-owned. Updates are thread-safe and apply to the
next live-start decision. Values must be finite, non-negative whole milliseconds
within `Int.MAX_VALUE`. Recordings (including growing ones) and ordinary
1.5-second rebuffers retain their existing policy. The zero-argument unstable
factory remains available with its original behaviour.

Live startup uses the configured time threshold without the delegate's byte-target
startup escape. Loading decisions remain delegated; its 15/30-second recording
limits are not backpressure on pushed live subscriptions. Prefer small,
seconds-scale thresholds: large values can prolong startup or prevent it if the
stream cannot accumulate enough buffered media. Lowering `buffer.duration`
allows the next decision to recover without rebuilding the Player. The accepted
integer range is not a practical buffer budget.

Pass that same handle to the coordinator overload to enable skip-aware restart
buffering (the coordinator type still has its existing Media3 unstable marker
until 0.28). Relative/absolute server seeks and return-to-live arm one restart,
including an already-running rebuffer. A restart begun while the command is in
flight or within 3 seconds after its completion uses the current live-start
buffer. Refusal, cancellation, target/period replacement, departure and teardown
clear the mark; uncertain dispatched outcomes retain the bounded grace.

The coordinator arms skip marks on the Player application looper and publishes
wire settlement immediately through the atomic fence on its actor. The 3-second
grace starts at command settlement, even if the application looper is busy.
The playback thread atomically consumes marks. Neither applications nor their learning
controllers should signal skips to load control themselves. One handle belongs
to one Player/coordinator pair. These overloads are the source-compatible bridge
to 0.28's broader SDK-owned configuration.

## Choose artifacts

Use one version for every SDK artifact. Replace `<released-version>` with the
version reported by the Maven Central badge in the [README](../README.md). The
version configured in a source checkout or staged under `build/local-maven` can
be newer than the latest public release.

An Android application that needs connection support and Media3 playback can
use:

```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:sdk-android:<released-version>")
    implementation("at.bernhardberger.tvheadend:sdk-media3:<released-version>")
    testImplementation("at.bernhardberger.tvheadend:sdk-testing:<released-version>")
}
```

The artifacts and their transitive API dependencies are:

| Artifact | Add it for | Transitive SDK API |
|---|---|---|
| `sdk-playback` | A pure-JVM, low-level subscription, seek, timeshift, and timestamp integration | None |
| `sdk-core` | A pure-JVM session with channel, EPG, DVR, and playback-binding APIs | `sdk-playback` |
| `sdk-android` | Android discovery, connectivity, profile storage, and Coil artwork integration | `sdk-core`, and therefore `sdk-playback` |
| `sdk-media3` | Android Media3 sources and the playback coordinator | `sdk-core` and `sdk-playback` |
| `sdk-testing` | JVM consumer fakes, scripted subscription events, and packet fixtures | `sdk-core` and `sdk-playback` |

`sdk-media3` also exposes Media3 ExoPlayer types, and `sdk-android` exposes Coil
types used by its artwork component. `sdk-android` and `sdk-media3` do not expose
each other, so add both when both feature sets are needed. A JVM application
that only needs session, catalog, EPG, and DVR operations can depend on
`sdk-core` alone. Add `sdk-playback` directly only for a custom low-level
playback integration that does not use `sdk-core` or `sdk-media3`.

The raw HTSP library is an implementation dependency of `sdk-core`. It remains
on the runtime graph but is not exposed on the consumer compile classpath. No
HTSP type is part of the public SDK API. JVM publications target Java 17;
Android publications require API 28 or newer.

## Own one session

`createTvheadendSession()` returns the process-wide session owner. Repeated
calls return the same instance until terminal `shutdown()` completes, so an
application should share it rather than construct feature-specific sessions.
Exactly one server profile and protocol generation can be active.

Calls without policies reuse the owner regardless of its configuration. Calls with policies
reuse it only when their configuration equals the active owner's; otherwise they throw
`IllegalStateException` with a configuration-free message. The EPG-only overload requests
caching disabled. Comparison and acquisition are atomic. After terminal shutdown completes,
new policies can be supplied. Both policy types use value equality; cache roots are stored
and compared as normalized absolute paths, without resolving symlinks or requiring the
directory to exist.

`connect(profile)` selects a profile and returns a `SessionCommandResult`. That
result reports command admission, not connection readiness. Observe the atomic
`TvheadendSession.observation` flow for the durable lifecycle:

| `SessionState` | Meaning |
|---|---|
| `Disconnected` | No connection lifecycle is active. |
| `Connecting` | The transport is being established. |
| `Synchronizing` | Initial metadata is loading. Retained same-profile snapshots may still be readable. |
| `Ready` | The channel, EPG, and DVR snapshots are authoritative for one connection generation. |
| `Unavailable` | A typed `SessionFailure` describes the safe failure and retry policy. |

`Ready` is published only after the server's authoritative initial metadata
fence. EPG coverage expansion, DVR configuration loading, and DVR disk-space
enrichment are supervised generation-owned background work; their independent
states may still be `Unknown` or `Synchronizing` after the session is ready.

`ServerCapabilities.apiVersion`, `allLimit`, `dvrLimit`, `streamingLimit`, and
`uiLevel` (including their `create()` parameters) are `Long?`, preserving the
server's unsigned 32-bit values without narrowing. `null` still means absent.
`SessionFailure.SERVER_UPGRADE_REQUIRED` means the server is below the supported
protocol floor (HTSP 36). Its recovery disposition is `EXPLICIT_RETRY`: upgrade
the server before retrying; changing the profile is not itself the remedy.
`SessionFailure.METADATA_OVERFLOW` triggers a full reconnect and resync with
increasing backoff, including when overflow follows a previously ready session.

Capture related values from one observation:

```kotlin
val observed = session.observation.value
val currentSession = observed.currentSession ?: return
val channel = observed.channel(channelId) ?: return
val currentProgramme = observed.eventAt(channel.id, now)
val nextProgramme = observed.nextEvent(channel.id, now)
```

`currentSession` is an opaque proof that the lifecycle and primary channel, EPG,
and DVR snapshots in that publication are current for the owning session and
generation. It becomes `null` in the same atomic publication that retires the
generation. A retained observation can still provide stale selectors for
display or comparison, but it cannot authorize a server round trip or a new
playback binding.

With `ExperimentalTvheadendApi` opt-in, `channelRefreshTimes`, `epgRefreshTimes`
and `dvrRefreshTimes` report local receipt times beside that observation's
content. `epgLastQueriedAt[channelId]` records successful accepted queries,
including empty or unchanged replies. Pair it with authority and coverage for
the displayed range; no one time proves the entire guide fresh. Null/absent
means unknown. Cached times retain their original receipt values, not disk
write/load times. Applications choose age thresholds and clamp negative ages
after wall-clock corrections; freshness never authorizes server actions.

Pass the captured `currentSession` to operations selected from the same
observation. If a disconnect, reconnect, or profile switch wins the race, the
operation returns a typed expiration or connection-change result instead of
silently rebinding to the replacement generation. Capture the replacement
observation and its new `currentSession` before retrying. Switching profiles
performs full teardown and discards the prior repositories before the new sync.

Use `session.isCurrent(currentSession)` for side-effect-free inspection and
`session.awaitCurrentSession(replaced = currentSession)` to suspend until a
distinct proof is published. Neither API leases a proof: it can retire
immediately after inspection or return, and cancellation of the wait always
propagates. `currentSession.generationIdentity` is an opaque, process-local cache
scope. It can remain the same when metadata temporarily retires and republishes
a proof under the same connection authority, but it cannot reconstruct or
authorize a proof.

`disconnect()` completes reusable teardown. `shutdown()` is terminal, ordered,
and idempotent. It affects every holder of the shared session; a later
`createTvheadendSession()` call creates a fresh owner.

### Persist metadata between processes

By default nothing survives the process. Pass a `MetadataCachePolicy` to keep
the last published channel catalog, guide and encoded artwork on disk:

```kotlin
val session = createTvheadendSession(
    epgCoveragePolicy = EpgCoveragePolicy.create(7.days),
    cachePolicy = MetadataCachePolicy.create(root = context.cacheDir),
)
```

The SDK owns the mechanics: files live under `<root>/tvheadend-sdk/<namespace>/`
where the namespace is a hash of host, port and username, the catalog is written
on every change, the guide is coalesced to one write per minute and flushed on
disconnect, and files older than `metadataRetention` (default seven days) or
that fail to decode are deleted. On the next `connect(profile)` cache restoration
runs alongside connecting. Snapshots available before authentication are published
as `Stale`; those restored during initial sync become the retained data in
`Synchronizing`. A restore finishing after initial sync is discarded. Connecting
does not wait for disk IO. The catalog is accepted before guide decoding, so a
channel list can render while the guide is still loading, or if guide restore fails.
If connecting fails before restore finishes, cached snapshots can still appear as
`Stale` for offline browsing and carry into the next connection attempt. Persistence
starts independently of restore completion, so disconnecting during a slow restore
still flushes any live metadata already synchronized.
Cached data is never published as `CURRENT`: tuning, EPG queries and DVR
mutations still wait for `Ready`. The guide is seeded for display only; the
initial sync re-sends programmes starting within the next six hours of server
time, and the SDK queries further programmes after `Ready` as for any other
uncovered range. At `Ready`, programmes from the cached guide (or, after a
same-profile reconnect, from the previously retained guide) that start beyond a
channel's live coverage remain in the `CURRENT` snapshot as display-only
carryover. Only channels whose live programmes the initial sync proved get
carryover, and only after that proven window; programmes the server named during
initial sync, such as deletions, never return as carryover. Carryover events are
outside `EpgSnapshot.coverages`: `EpgCoverage`, `knownTo` and acquisition results
describe live data only, and carryover never claims live proof. Wherever live
data reaches, including through later queries or the sliding server window, only
live events are shown, and that carryover stays gone even if the live data later
shrinks; an event ID present live always uses the live version, and a server
deletion removes the carryover copy. Carryover stays visible until its start
falls within the six-hour live window or a query reaches it, so its data can be
up to roughly `EpgCoveragePolicy.futureHorizon` minus six hours old. A restored
guide only reaches device time plus 24 hours (about 18 hours with the default
policy), but a same-profile reconnect can carry over explicitly acquired
programmes up to the configured horizon. `event(id)`, `eventAt`, `nextEvent` and
the DVR lookups by event ID may return a carryover event for display; DVR
mutations remain server validated. Retention and
`EpgCoveragePolicy.maximumRetainedEvents` apply to carryover too, which is
evicted before live events and never causes a live capacity refusal. A profile
switch never carries guide data over. Ordinary queries extend each channel in
four-hour steps to about 20 hours ahead over roughly 30 minutes; later
programmes, up to the configured `EpgCoveragePolicy.futureHorizon`, need
explicit coverage acquisition.
Tvheadend pushes programme updates only for programmes starting within its
sliding six-hour window. Programmes further ahead are refreshed when the worker
queries them again or when they enter the window, so late schedule changes can
show late; deletions are pushed immediately.

Guide restore includes events starting through the device clock's current time
plus 24 hours (whole seconds), dropping events that ended more than six hours ago.
This same window is available offline. Restored coverage and query horizons are
capped at that cut; persisted query receipt times remain display metadata and do
not supply live initial-sync scheduling proof. Persistence still writes the full
live guide, including its far-future events, plus any visible carryover events
without coverage, so a short session does not shorten the next warm start.

Guide persistence streams individual protobuf records rather than buffering an
encoded copy of the entire guide. The `EPG4` record stream orders events by start
time and ID, allowing restore to stop before decoding the far-future tail. Live
insertion order is not retained in restored stale event lists. Earlier
formats, including `EPG3`, are discarded once and repopulated by normal
synchronization, without affecting the catalog, artwork or application settings.
Cache records are bounded to 1 MiB each and 250,000 events/coverage entries per
snapshot. An oversized replacement is not persisted; the last complete cache
remains available. These storage limits do not truncate live guide metadata.

Artwork hits avoid a server file load. `artworkRetention` defaults to 30 days
from storage (reads do not extend it); `artworkMaxBytes` defaults to 64 MiB
across every namespace under the root. Least recently accessed entries are
evicted after writes, and expired entries are pruned on restore and writes.
Oversized artwork is returned but not persisted. Corrupt bytes and indexes are
discarded. The root must be app-private storage; cached bytes are not encrypted.
Use one owning session runtime per root, not multiple application processes.
Version 0.9.1 discards recognized legacy SDK namespaces on restore rather than
migrating ambiguous identities. `ArtworkLoader.cacheKey` values change once on
upgrade, causing one-time misses in application-owned artwork caches; those
caches retain their own eviction policy and are not cleared by the SDK.
Removed profiles' files remain subject to retention and the byte budget until
pruned by cache activity, or can be deleted immediately with the root-wide `clear()`.

`session.cache` exposes `SessionCache.statistics` for a storage screen and
`clear()` to delete every cached namespace. Statistics are measured once the
session first touches the cache, and a connected session persists its current
snapshots again right after `clear()`, so the control resets stale or damaged
files rather than freeing storage. That rewrite is the current in-memory
publication, which may include guide carryover restored before the clear. A
session created without a policy reports empty statistics and `clear()` is a
no-op. `FakeTvheadendSession.cache` is a `FakeSessionCache` whose statistics can
be scripted.
Statistics include encoded artwork bytes and entry count (excluding index
overhead). Clearing deletes artwork, including other profiles, and fences writes
from fetches already pending at deletion. New loads can cache again. It does not
clear Coil's application-owned memory cache or already-displayed images.

## Read the catalog

`SessionObservation.channelState` is `Empty`, `Synchronizing`, `Current`, or
`Stale`. The non-empty states carry an immutable `ChannelCatalog` containing
channels and tags. `Synchronizing.staleCatalog` is optional; `Current.catalog`
is authoritative for the active generation; `Stale.catalog` belongs to an
inactive generation.

Use the deliberately display-only projections when rendering whole retained datasets. They return
the immutable catalog or snapshot already held by the repository state without another copy:

| Repository state | Display projection | Authority |
|---|---|---|
| `Empty` | `null` | `ABSENT` |
| `Synchronizing` without retained data | `null` | `SYNCHRONIZING_WITHOUT_RETAINED_DATA` |
| `Synchronizing` with retained data | Exact retained value | `SYNCHRONIZING_WITH_RETAINED_DATA` |
| `Current` | Exact current value | `CURRENT` |
| `Stale` | Exact retained value | `STALE` |

The state-level properties are `channelCatalogForDisplay` and `channelCatalogAuthority`,
`epgSnapshotForDisplay` and `epgSnapshotAuthority`, and `dvrSnapshotForDisplay` and
`dvrSnapshotAuthority`. `SessionObservation` forwards the same properties for aggregate reads.
`RetainedMetadataAuthority` describes data provenance and synchronization only. It does not make
data selectable or authorize actions or retries; only `currentSession` proves aggregate mutation
and playback authority for the current connection generation.

Use observation point selectors when selecting related entities:

| Selector | Result |
|---|---|
| `channel(id)` | One channel from the current or retained catalog |
| `channels(ids)` | Matching channels in catalog order |
| `event(id)` | One retained EPG event |
| `eventAt(channelId, instant)` | The event active at the closed-start, open-stop instant |
| `nextEvent(channelId, instant)` | The linked or deterministically timed next event |
| `dvrEntry(id)` | One current or retained DVR entry |
| `dvrEntryForEvent(eventId)` | The highest-ranked recording linked to an event |
| `dvrEntryForEvent(event)` / `dvrEntriesForEvent(event)` | Experimental ranking for a held EPG event, including search results and historical programmes |
| `epgEventForDvrEntry(entryId)` | The unique event linked to a recording |

`dvrEntryForEvent` ranks matches lexicographically: entries owned by the session's
account first, then RECORDING/RECORDING_ERROR, then SCHEDULED, then the event's
`dvrEntryId` link, latest stop (unknown last), and lowest entry ID. Owner comparison
is exact after the SDK's username normalization; anonymous sessions and missing
owners receive no ownership preference. Tvheadend's event `dvrId` is just its first
visible broadcast match and is not authoritative. The selector returns null when
the event ID is unknown or ambiguous, no linked entries exist, or matched entries
are ambiguous because they contain duplicate DVR entry IDs. Unlinked time-window
matches are not considered. Standalone observations and testing factories have no own account.

With `@OptIn(ExperimentalTvheadendApi::class)`, `dvrEntriesForEvent(eventId)` returns
`DvrEventEntries` with the same `selected` entry and an immutable `otherActive`
list of remaining SCHEDULED/RECORDING/RECORDING_ERROR matches in rank order.
It returns null exactly when `dvrEntryForEvent` does. This lets consumers identify
the selected target and any other active entries before a mutation.

The experimental `EpgEvent` overloads use the supplied event without looking it up
in the observation's EPG snapshot. They match only explicit entry/event links and
use the held event's `dvrEntryId` as given, even if stale. Ranking and duplicate-entry
ambiguity rules are identical to the ID overloads; the ID overloads still require
a unique event in the observation's snapshot. Event and DVR entry IDs are
server-scoped: pass only events obtained from the same server profile, and drop
held search or history results when the profile changes.

`Channel.tagIds` and `ChannelTag.channelIds` expose catalog membership. Entity
lists are immutable and preserve SDK catalog order. Presentation filtering,
grouping, and UI policy are not SDK APIs.

`channelComparator()` returns the standard Tvheadend channel order: numbered
channels by major number, minor number (absent first) and name, then unnumbered
channels by name, with missing or blank names last and `ChannelId` as the final
tie-break. Pass a `java.text.Collator` to choose the name locale; the default
uses the JVM default locale. Tvheadend reports `0` for an unnumbered channel, so
use `Channel.hasChannelNumber` before displaying or dialling `number`.

`EpgEvent.contentGenre` and `DvrEntry.contentGenre` decode the raw ETSI EN 300 468
`contentType` byte. `category` is the level-one `ContentCategory`; `subgenre` is
the level-two `ContentSubgenre`, or `null` for a category's general, user-defined
or unassigned value. Show `category` alone or both. The duplicated raw
`contentType` property is no longer exposed.

With `@OptIn(ExperimentalTvheadendApi::class)`, `EpgEvent.credits` supplies an
immutable, ordered list of `EpgCredit(name, role)` for a cast-and-crew section.
Null means the server sent no credits (normal for DVB EIT and basic HTSP output;
XMLTV needs "scrape extra"). Events restored from a pre-0.28 cache also have null
credits until the next sync. Compare `EpgCreditRole.ACTOR`, `DIRECTOR`, `GUEST`,
`PRESENTER`, or `WRITER`, and keep a fallback using `role.raw` for unknown roles.
Each wire name/role pair is retained in server order, so the same name can appear
with several roles. Do not key the list by name alone. Omitted credits in
a full update clear existing credits to null; an explicit empty list replaces
them with an empty list. DVR entries do
not carry credits; use the associated EPG event when available.
The stable `EpgEvent.create` factory remains available without opt-in. Its
experimental overload requires an explicit `credits` argument (including null).

## Search and extend EPG coverage

The retained `EpgRepositoryState` follows the same `Empty`, `Synchronizing`,
`Current`, and `Stale` freshness model as the catalog. An `EpgSnapshot` pairs
its immutable events with per-channel `EpgCoverage`; read both from one captured
observation.

Explicit text search is server-backed and generation-bound:

```kotlin
val request = EpgSearchRequest.create(
    query = "news",
    fullText = true,
    channelId = channel.id,
)

when (val result = session.epgRepository.search(currentSession, request)) {
    is EpgSearchResult.Available -> consume(result.events, result.originatingSession)
    is EpgSearchResult.Failed -> when (result.failure) {
        TvheadendFailure.OBSERVATION_EXPIRED, TvheadendFailure.CONNECTION_CHANGED -> Unit
        else -> Unit // Open failure set: always include a fallback.
    }
}
```

`originatingSession` is the exact proof that admitted the successful search. It
records provenance only; the proof may already be retired when the result is
observed. Check current state or wait for a replacement before a new operation.

Search does not read or mutate retained coverage. `Failed(SERVER_REJECTED)` also
covers the server's generic rejection of inaccessible channel or tag filters; an
unusable payload is `Failed(MALFORMED_RESPONSE)`. Search durations, when supplied,
must be finite, non-negative whole seconds; content type is an unsigned eight-bit
genre code.

Acquire a bounded future horizon separately:

```kotlin
when (
    val result = session.epgRepository.acquireCoverage(
        currentSession = currentSession,
        channelId = channel.id,
        through = requestedBoundary,
    )
) {
    is EpgCoverageAcquisitionResult.CoveredWithData -> consume(result.observation)
    is EpgCoverageAcquisitionResult.CoveredEmpty -> consume(result.observation)
    EpgCoverageAcquisitionResult.Ineligible -> Unit
    EpgCoverageAcquisitionResult.ObservationExpired -> Unit
}
```

Guide screens can acquire one shared horizon for an ordered visible-channel selection without
launching or serializing singular calls:

```kotlin
val result = session.epgRepository.acquireCoverageBatch(
    currentSession = currentSession,
    channelIds = visibleChannels.map(Channel::id),
    through = requestedBoundary,
)

result.settlements.forEach { settlement ->
    when (settlement) {
        is EpgCoverageBatchSettlement.CoveredWithData -> consume(settlement.observation)
        is EpgCoverageBatchSettlement.CoveredEmpty -> consume(settlement.observation)
        is EpgCoverageBatchSettlement.TargetAbsent -> Unit
        is EpgCoverageBatchSettlement.Rejected -> Unit
        is EpgCoverageBatchSettlement.ObservationExpired -> Unit
    }
}
```

Duplicate IDs settle once at their first position. The session's EPG worker retains ownership of
cooldown, fairness, spacing, coalescing, and bounded query concurrency. Cancelling the batch
promptly removes only its waiters; shared worker work remains available to other callers. Visible
channel selection, horizon choice, paging, pending UI, timeout copy, and retry policy stay in the
application. `acquireCoverage` remains available for focused callers.

Covered results carry the exact immutable `SessionObservation` that established
the answer; use that observation rather than recapturing a potentially newer
one. `CoveredEmpty` proves the query succeeded without retained programmes.
`Ineligible` covers an unknown channel, a boundary outside policy, or missing
server query capability.

The default `EpgCoveragePolicy` keeps a 24-hour future horizon and at most
100,000 events. A custom policy supplied to `createTvheadendSession(policy)` can
request 24 hours through 7 days and 1 through 250,000 retained events. The
policy configures a fresh process-wide session owner; a conflicting active policy is rejected.

### Already-received programme history

`observation.epgSnapshotForDisplay?.historicalEvents` contains deleted, ended
programmes retained for guide history and rewind labels. `observation.eventAt`
searches the live schedule first, then this history, using `[start, stop)` timing.
For a guide's past window, combine `events` and `historicalEvents` and select
the desired time range: ended events still present on the server remain in `events`.
Both are display APIs: a historical event ID never proves that the server still
has the event or can record it. `events`, `event(id)`, `nextEvent`, coverage and
DVR associations continue to use the server schedule, excluding this archive.

The SDK retains only programmes already received in the active generation whose
stop is at or before the generation's estimated server time when deletion arrives,
or whose end is proven by the server's channel-current event advancing to a
non-overlapping successor. That channel evidence handles the whole-second time
estimate lagging just behind an actual stop boundary.
Unknown-time, current and future deletions are removed without archival. HTSP
does not identify the deletion reason: a correction deleting an already-ended
programme is indistinguishable from expiry. Accepted same-ID corrections or
same-channel overlapping replacements supersede archived metadata.

History uses the existing six-hour past window, with an additional bounded pool
of at most `maximumRetainedEvents` entries (oldest stop first eviction). It does
not consume live-schedule capacity. Pruning occurs on metadata traffic and the
EPG worker's maintenance cycle (normally at most ten minutes when idle); captured
immutable observations do not change with time. Channel deletion removes its
history. Reconnect starts a fresh archive and the new authoritative snapshot
replaces prior history; prior same-profile snapshots can remain explicitly stale
during synchronization. Profile reset clears the repositories. History is not
persisted across processes. Cold starts and reconnects can therefore have gaps;
server queries cannot recover programmes the server has already destroyed.

This metadata lookup does not provide a timeshift-stream-to-UTC mapping or prove
that a programme remains in the server's seekable buffer.

## Read and mutate DVR state

### Display a mutation rejection

With `@OptIn(ExperimentalTvheadendApi::class)`, `DvrMutationResult.Failed.serverMessage`
provides optional untrusted, display-only text for `SERVER_REJECTED`. Tvheadend
translates its catalogue errors into the connection/account language: never match
strings to infer permission, state or retry policy. The SDK first bounds input to
1024 UTF-16 code units, NFC-normalizes, replaces control, format and line/paragraph
separator code points, trims and bounds display text to 256 UTF-16 code units
(without splitting surrogate pairs);
blank text and non-server failures return null. Render as plain text, never markup,
and never put it in logs or diagnostics. Equality, hashing and `toString()` ignore
the message; `failure` and `mayHaveApplied` keep their existing meaning.
Consumer fakes can script the three-argument `Failed(failure, mayHaveApplied, serverMessage)`
constructor through `FakeDvrRepository`.

### Observe DVR changes

`DvrRepository.changes: Flow<DvrChange>` carries server-observed lifecycle changes,
not command results. Collect it in an application-owned scope:

```kotlin
session.dvrRepository.changes.collect { change ->
    if (change.generationIdentity === session.observation.value.currentSession?.generationIdentity) {
        consume(change)
    }
}
```

`previous` is absent for a newly added entry; `current` is absent for removal.
Initial synchronization and reconnect snapshots never replay as changes. Statistics-only,
unknown-entry updates/deletes and same-state full updates produce no change. A first
transition from `SCHEDULED` or `RECORDING` into an error state emits
`RECORDING_FAILED` at most once per entry per generation, even if it recovers and
fails again. Removal clears that marker. A missing file after successful completion
is not a recording failure. Invalid transitions produce no change.

This is a hot, best-effort stream with no replay and a 64-change buffer that drops
the oldest item for slow collectors. Events are lost when nobody is collecting.
Use `SessionObservation` DVR snapshots as the authority, not this stream as an audit
log. A buffered event may belong to a retired generation; compare the opaque identity
with the current session before acting on it. Presentation, grouping and notice timing
remain application-owned.

`origin` is `External` unless the SDK can correlate a matching command from this
client (`ThisClient(mutation)`). Intents are registered before sending, including
programme schedules matched by event ID, so an event may precede command acceptance.
Rejected/failed or cancelled unaccepted commands drop their intents; accepted intents expire after 60 seconds,
and generation rebind or admission stop clears them. Correlation is best-effort, not
proof of causality or command success, and does not change mutation confirmation.
Manual schedules without an event ID are not attributed by this ledger.
An event attributed before a command later fails is not retracted. Edits do not
create lifecycle intents. A programme schedule remains correlated through its
`SCHEDULED` add and subsequent `RECORDING_STARTED` (or expiry), bound to that entry
ID after the add. A delete may attribute both the terminal recording update and
`REMOVED`; its intent is consumed only by removal (or expires).

A server user-abort marker emits `RECORDING_ABORTED`, never `RECORDING_FAILED`.
Its snapshot state remains `COMPLETED_ERROR` for compatibility. A local `STOP`
intent wins over terminal error/abort kinds and yields `RECORDING_STOPPED`. For an
external successful completion, the latest recording-file stop time (when present),
otherwise the generation's estimated server time at acceptance, must be more than
60 seconds before the entry's scheduled `stop` to classify `RECORDING_STOPPED`.
This tolerance allows for server-time estimate skew. Padding is not subtracted or
added. Within that tolerance, at/after the stop, or without enough timing evidence, the
change is `RECORDING_COMPLETED`. Tvheadend sends no distinct external-stop marker;
early completion therefore remains a timing inference, and late external stops are
indistinguishable from natural completion.

Application tests can use `DvrChange.create(...)` and
`FakeDvrRepository.emitChange(change)`. Emission does not update the fake snapshot;
publish observation state separately when a test needs both.

### Failed-recording visibility

When `ServerCapabilities.failedDvr == CapabilityAccess.DENIED`, the account lacks
Tvheadend's failed-recordings right. Once an entry counts as failed, Tvheadend
stops sending `dvrEntryUpdate` for it and sends no `dvrEntryDelete`. The SDK keeps
the last observed state, which may still be `SCHEDULED` or `RECORDING`; a reconnect
removes the now-hidden entry from the synchronized snapshot.

Mutations on that entry return `SERVER_REJECTED` with an access message (available
through the experimental display-only `serverMessage`). The SDK does not infer a
recording failure from rejection: owner mismatch can produce the same response.
Do not match the localized message text.

A consumer can present an entry as **possibly failed** when `failedDvr` is `DENIED`,
its last observed state is `SCHEDULED` or `RECORDING`, and the current time is past
its `stop` plus post padding and an application-chosen grace period. This is a
presentation heuristic, not a confirmed state transition or `RECORDING_FAILED`
event. Tvheadend implements this visibility filtering in `dvr_entry_verify`
(`src/dvr/dvr_db.c`) and its HTSP DVR update delivery (`src/htsp_server.c`).

### Snapshots and commands

DVR fields use `java.time.DayOfWeek`, whole-minute `java.time.LocalTime` and
`kotlin.time.Duration`. `daysOfWeekMask` now takes a set (Monday is wire bit zero).
Automatic-rule bounds use `AutorecTime.At(time)` or `AutorecTime.AnyTime` (wire 1440,
distinct from midnight). Sub-minute times, non-finite or fractional-minute padding
throw `IllegalArgumentException`; signed whole-minute padding is supported.
`DvrEntry.duplicate` is nullable Boolean. Entry images, fanart and EPG images use
`ArtworkId`; unsupported image references are omitted. Genre uses `contentGenre` only.

`DvrEntry.episode` includes `seasonCount` and `onscreen` when supplied by the
server. Full add/update notifications replace channel, tag, EPG, DVR-entry and
recording-rule entities, inserting unseen IDs and clearing omitted optional
values. The exception is omitted tag membership, which retains known members;
an empty list clears membership. Channel now/next and DVR-statistics messages
patch only their own fields, including clearing omitted optional statistics.
Patches for unknown IDs are ignored until a full entity arrives. Cached snapshots
are display-only during synchronization and do not fill omissions in fresh entities.
`DvrEntry.folder` is recomputed from the current server paths; application-supplied
folders belong to application-created entries, not the live reducer.

Malformed metadata is skipped before and after initial synchronization: a successful
acknowledgement and sync marker let the session reach `Ready`, and later messages
continue to apply without reconnecting or resynchronizing. Snapshots may remain
incomplete for that generation; the next
reconnect performs a full sync and replaces reduced state and cached snapshots.
Reports for retired generations are ignored. A malformed synchronization
acknowledgement still fails with `MALFORMED_RESPONSE` and requires explicit retry.
Metadata delivery overflow still retires the generation and reconnects with backoff
for a full resync.

With
`@OptIn(ExperimentalTvheadendApi::class)`, time-based rules expose `removalDays`
as a nullable `Long`, matching `AutorecRule.removalDays` (unsigned 32-bit server
policy domain, `null` means unknown). Fakes can use
`TimerecRule.create(id, removalDays = 30)`. Rule updates replace their snapshots,
so omitted rule fields become unknown rather than retaining an old policy.

The same opt-in exposes nullable, read-only `owner` and `creator` on entries and
both rule types. These are server-reported account metadata for application
display; keep them out of logs and diagnostics. Model `toString()` remains
redacted. Public create factories leave ownership unset.

`DvrPriority`, `DuplicateDetection` and `BroadcastType` are open sets: retain an
`else` when comparing values. Unrecognized server values map to each type's `UNKNOWN`.
`UNKNOWN` has no wire value and every create/update request rejects it with
`IllegalArgumentException`. Leave an unknown setting unchanged rather than copying it
into a request: every update field defaults to `null`. Only explicit known values are sent.

```kotlin
// Other settings, including unknown server policies, stay untouched.
val rename = AutorecRuleUpdate(title = "Evening recordings")
val window = AutorecRuleUpdate(
    daysOfWeekMask = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY),
    startMinutesSinceMidnight = AutorecTime.At(java.time.LocalTime.of(20, 0)),
    startWindowEndMinutesSinceMidnight = AutorecTime.AnyTime,
    priority = DvrPriority.HIGH,
)
```

`SessionObservation.dvrState` carries immutable entries, automatic-recording
rules, and time-based rules through the `Empty`, `Synchronizing`, `Current`, and
`Stale` freshness states. DVR configuration and disk-space enrichment are
separate:

| Observation field | Typed states |
|---|---|
| `dvrConfigurationsState` | `Unknown`, `Synchronizing`, `Current`, `Stale`, `Denied` |
| `dvrDiskSpaceState` | `Unknown`, `Synchronizing`, `Current`, `Stale` |
| `recordingProgressCapability` | `UNKNOWN`, `SUPPORTED`, `UNSUPPORTED` |

`TvheadendSession.dvrRepository` provides generation-bound commands to
schedule, update, stop, cancel, or delete entries and to create, update, or
delete automatic and time-based rules. For example:

```kotlin
val request = DvrScheduleRequest(
    schedule = DvrSchedule.Programme(event.id),
)

when (val result = session.dvrRepository.scheduleEntry(currentSession, request)) {
    is DvrMutationResult.Confirmed -> select(result.value)
    is DvrMutationResult.AcceptedButUnconfirmed -> select(result.value)
    is DvrMutationResult.Failed -> {
        if (result.mayHaveApplied) {
            // Reconcile authoritative metadata before deciding whether to try again.
        }
        when (result.failure) {
            TvheadendFailure.ACCESS_DENIED -> Unit
            else -> Unit // Open failure set: always include a fallback.
        }
    }
}
```

`Confirmed` means matching authoritative metadata was published.
`AcceptedButUnconfirmed` means the server accepted the command but confirmation
did not arrive before the deadline; later observation data remains
authoritative. Mutations and progress are admitted only for a ready generation.
Cancellation remains caller-owned and is not converted into a failure result.

For `updateEntry`, the SDK always resolves an explicit `DvrEntryUpdate.configId`
with a same-generation `getDvrConfigs` request, regardless of observed state or
whether the entry has been observed. When null, it resolves only a last-observed
`SCHEDULED` entry's current config UUID. It sends the matching enabled,
account-visible config's **name**, including `""` for the default config. This
preserves the profile on edits such as changing the title. Without an explicit
UUID or an observed scheduled entry's config UUID, the field remains omitted.
An unobserved entry that is scheduled server-side may then move to the default
profile. The UUID must match exactly one returned
config, and its name must be unique in that list. Missing or ambiguous matches,
or a changed entry identity/state/config during resolution, fail with
`OBSERVATION_EXPIRED` and `mayHaveApplied=false`. Missing/ambiguous config failures
persist until the caller selects an explicit visible, unambiguous config or the
server-side config changes; refreshing alone does not clear them. Lookup failures
propagate with `mayHaveApplied=false`; access denial updates DVR write-access proof
like a refused write. Other entry states without an explicit config, watch-state
changes, resume reset, and playback progress do not gain this lookup.

**Known server limitations:** with an ACL config list, Tvheadend can translate
the name back to a UUID before the entry update's name-only lookup, silently
selecting the default profile. The client cannot detect this from the config
list. A config rename in the small interval between lookup and update can also
cause default fallback. Observe authoritative metadata after the update.

`DvrMutationResult.Failed` and `DvrProgressResult.Failed` carry `mayHaveApplied`.
It is false only when the command was never sent or the server explicitly refused
it. It is true for uncertain dispatched commands and failures after acceptance,
including generation retirement while awaiting confirmation. This is operation
stage information, not a property of `TvheadendFailure`. Its `isTransient`
predicate does not authorize automatic retry.

Explicit-time schedules require a non-empty title (whitespace is permitted);
programme schedules can omit it. Supplied start/stop minutes for time-based
rule creation and updates must be in `0..1439`. Automatic-recording rule windows
retain their separate `1440` sentinel semantics.

The experimental `dvrRepository.updateWatchState(currentSession, id, watched)`
marks watched (`true`) or unwatched (`false`) and **always clears resume** in one
command. Watched sets a zero play count to one and preserves a positive count;
unwatched resets it to zero. It sends for every entry state, including ids absent
from the local snapshot. `DvrProgressResult.Accepted` means a successful reply,
not confirmed changes: the server may ignore progress on scheduled, missed or
invalid entries. Observe metadata for actual changes; later playback checkpoints
can overwrite the reset. An expired observation fails before dispatch with
`OBSERVATION_EXPIRED` and `mayHaveApplied=false`. A per-entry refusal does not
disable progress for other entries. Experimental `dvrRepository.resetResume(currentSession, id)`
clears resume without changing play count: one command sends position zero and omits
play count (the server's KEEP semantics). It has the same eligibility, generation,
failure and metadata-confirmation rules as `updateWatchState`.
`sdk-testing` provides experimental result scripting and immutable, safely
rendered `watchStateRequests` on `FakeDvrRepository`.

Cutpoints and playback progress belong to an observation-bound
`PlaybackBinding.Recording`, not to the mutation repository. `cutpoints()`
returns `DvrCutpointsResult.Available` or `Failed(failure: TvheadendFailure)`.
Cutpoint actions are metadata;
the SDK does not impose commercial-skip or scene policy. The Media3 coordinator
handles supported progress reporting. Custom low-level integrations that opt in
to `SubscriptionInfrastructureApi` can use `DvrProgressPolicy` and the binding's
progress API.

## Bind Media3 playback

`sdk-media3` supplies a narrow coordinator over an application-owned
`ExoPlayer`. Use its structured lifetime boundary, capture one exact current
session proof, and install the live target in one operation:

```kotlin
val coordinator = createTvheadendPlaybackCoordinator(player)
val shutdownResult = coordinator.withLifetime(2.seconds) { activeCoordinator ->
    val observed = session.observation.value
    val currentSession = observed.currentSession ?: return@withLifetime
    val channel = observed.channel(channelId) ?: return@withLifetime
    launch {
        activeCoordinator.livePlaybackObservation.collect(::renderLivePlayback)
    }
    when (
        val target = activeCoordinator.setLiveTarget(
            session = session,
            currentSession = currentSession,
            channelId = channel.id,
            options = LivePlaybackOptions(timeshiftPeriod = 30.seconds),
        )
    ) {
        is LivePlaybackTargetResult.Bound -> handlePlaybackTargetResult(target.result)
        LivePlaybackTargetResult.ObservationExpired -> handleExpiredObservation()
        LivePlaybackTargetResult.TargetUnavailable -> handleUnavailableChannel()
    }
}
handlePlaybackShutdownResult(shutdownResult)
```

`withLifetime` claims the one-shot coordinator before invoking the block. The
block receiver is a scope for lifetime-bound collectors; all of its child jobs
are cancelled and joined before terminal shutdown evidence is returned.

The live overload taking `session`, `currentSession`, and `channelId` performs
exactly one generation-bound bind and, only when binding succeeds, one normal
coordinator installation. `LivePlaybackTargetResult.ObservationExpired` and
`TargetUnavailable` preserve binding failures. `LivePlaybackTargetResult.Bound`
retains the exact `PlaybackTargetResult`, including `STARTED`, `DEPARTED`, coordinator
lifecycle failures, target failures, and `PLAYER_UNAVAILABLE`; it does not imply
that installation started. This operation never reacquires a generation,
retries, or retains the channel selection for reconnect.

Binding remains lazy: a successful bind does not itself create a subscription
or file resource. The existing coordinator replacement transaction remains the
installation boundary, so a failed replacement either restores the healthy
prior target or reports retirement under the coordinator's documented failure
semantics. Recording consumers still bind once with
`session.bindRecordingPlayback(currentSession, recordingId)` and pass that
exact binding to `setRecordingTarget()`.

`setLiveTarget()` and `setRecordingTarget()` return `PlaybackTargetResult`:
`STARTED`, `DEPARTED`, coordinator lifecycle failures, `NOT_READY`,
`RECORDING_PROGRESS_UNSUPPORTED`, `TARGET_UNAVAILABLE`, the typed growing
recording limitation `GROWING_RECORDING_DEFERRED`, or `PLAYER_UNAVAILABLE`. Handle the result before
assuming Media3 accepted the target. For either live or recording installation,
`DEPARTED` has `isStarted == false`: the application replaced the Player item during
installation, but the target remains installed until `stop()`, replacement, or `shutdown()`.
Exact values are SDK-owned singletons, not
an exhaustive enum. Use `isStarted`, `disposition`, or the non-exclusive
`categories` and retain a fallback when matching an exact value. `isTransient`
means the condition may change; it does not promise that replay is safe.

Discover stream profiles for the same captured generation with
`session.getStreamProfiles(currentSession)`. `StreamProfilesResult.Available`
contains the immutable server order and its exact `originatingSession`. That
proof is provenance, not a currency guarantee, and may already be retired when
the result is observed. Other outcomes distinguish not-ready or expired
observations, safe server failures, transport loss, and unsupported discovery.
Pass an available opaque `StreamProfileId` through `LivePlaybackOptions`; omit
it to keep the server default.

### Timeshift

A positive requested timeshift period is only a request. The active server must
grant a positive period before `timeshiftState` becomes
`LiveTimeshiftState.Available`. Its buffered duration, position behind live, and
server-pause fields remain nullable until valid ordered status events arrive.
`positionBehindLive` is the **server reader's** shift, never displayed content.
The grant is capacity, not proof of available history. The optional `timeline`
contains only the latest valid observed absolute `start` and `end`; it does not
advance with the local clock or expand to fill the grant.

For content-stable selection, retain `timeline.select(position)` and pass that
opaque `TimeshiftContentTarget` to `seekTimeshift(target)`. Its `position` remains
the same absolute stream coordinate as the live edge advances. Do not retain a
relative offset from live and recompute it when the user confirms. Selection
outside the observed range returns null. At execution, missing history returns
`Unavailable`, a target outside current history returns `Expired`, and a target
from a replaced, detached or retired subscription returns `Replaced`. The SDK
does not clamp or send an old target to a successor, including same-channel
period recreation and retry. Targets are in-memory subscription capabilities,
not persistable programme identifiers. History can expire after validation;
the server's keyframe choice may differ from the requested coordinate.

`TimeshiftContentSeekResult.Completed` carries the command outcome and nullable
`readerReached`, an absolute acknowledgement in the same coordinate contract.
Null means unknown, not the requested target. Even a known reader position is
not a frame-on-screen acknowledgement. An in-flight replacement returns
`Replaced` rather than attributing the old acknowledgement to the new target.

Call `timeshiftPlaybackPosition()` at the application's chosen observation
cadence. The coordinator samples Media3 on its application looper, fences the
subscription before and after sampling, refuses mapping while multiple periods
coexist, and maps that output coordinate through
bounded packet-evidence segments. `Estimate.target` uses the same content
coordinates as selection. It is explicitly an estimate, not rendered-frame
proof: Media3 samples position in milliseconds, elementary readers may derive
sample timestamps, and decoder latency is not observed. Pause leaves the
sampled position in its old segment even if packets or server status advance.
Queued pre-seek content retains its old mapping while post-seek timestamps use
their own rebase offset. Gaps, overlapping segments that disagree, missing packet
timestamps, absent player snapshots, replacement, and evicted mapping history
return `Unavailable`; no extrapolation beyond observed packet endpoints occurs.
At most 64 segments are retained. An estimated displayed coordinate can outlive
its server-seekable history, so a subsequent seek can still return `Expired`.

Wall-clock mapping is separate: `timeline.wallClockMapping` is currently
`UNAVAILABLE`. Absolute stream durations are not Unix time. There is no supported
UTC anchor in the inspected status / elementary-packet path, and the SDK does
not label local receipt time minus a shift as programme time. Thus this path
offers supported stream-coordinate selection, estimated playback-coordinate
mapping, and unavailable programme-time mapping, not an authoritative or
estimated programme timestamp. Consumers must not substitute EPG start time or
the device clock to imply SDK-supported precision. Kotlin `Duration` supplies
the maintained unit representation; no new time codec or clock conversion is
introduced.

`seekTimeshiftBy(offset)` captures a client playback-position anchor, resolves a
clamped selection and returns `TimeshiftContentSeekResult`. For a key-driven
preview, use the latest timeline's `resolveSelection(anchor, previous, delta)`
and commit with `seekTimeshift(selection)`. See the README for moving-edge and
stage-outcome semantics. `returnToLive()`
requests the bounded near-live position, not a separate exact-live mode.
`pauseTimeshift()` and `resumeTimeshift()` control server delivery only; normal
Media3 play and pause remain application-owned. Return-to-live and speed commands return
`TimeshiftCommandResult`, which distinguishes acceptance or rejection,
unavailable or pending state, acknowledgement and queue failures, subscription
end, safe server-operation failures, unsupported behavior, and coordinator
lifecycle failures. Use `isAccepted`, `isTransient`, `isTerminal`,
`isUnsupported`, `isConfigurationOrAccessRelated`, and `isOutcomeUncertain`, or
the corresponding disposition and categories. Exact values are non-exhaustive,
so exact-value matching must include a fallback. `UNCONFIRMED` means the SDK
cannot prove whether the server accepted the command; those outcomes are also
uncertain. Acceptance and categories are independent: for example, an accepted
seek can still be terminal when its resumed segment cannot be anchored safely.

`livePlaybackObservation` is the conflated-latest aggregate for consumers that
need timeshift state, subscription issue, and diagnostics from one target-fenced
publication. While the target owns the Player item it emits `Active` when any of
those components changes. Ownership loss emits `Departed`; retirement resets
to `NoTarget` as one retirement transition before stale target callbacks can
publish again. `timeshiftState`, `subscriptionIssue`, and `liveDiagnostics`
remain available for consumers interested in only one component; independently
collecting those flows does not create an atomic aggregate snapshot.

`Active.serverStopped` is true while the server has stopped the live stream and
no later `Started` has arrived. No media is delivered during that interval. The
server may stop without any issue, so a `null` `subscriptionIssue` alone does not
mean the stream is healthy; show an interruption state from `serverStopped` and
add the issue text when one is present. The flag clears when the stream starts
again, the subscription ends, or the target is retired.

To tell a live item from a recording, use `MediaItem.isTvheadendLive()` on the
player's current media item. It is true for items that carry the media id the
SDK reserves for its live source, which the live source sets on its items,
including during timeshift. SDK recording items, including growing recordings,
never carry it. The check compares only the media id, so do not use that id for
your own items, and do not match media ids or URIs yourself.

`subscriptionIssue` exposes only the current live target's canonical
`SubscriptionIssue`. Unknown or localized server values map to `UNKNOWN`; raw
server text is not exposed. The exact no-input status maps to `NO_INPUT` unless
a conflicting known canonical error is present. The state clears when the
target or lifecycle no longer owns that issue. `stop()` returns
`PlaybackStopResult.stopped(...)` or `PlaybackStopResult.retiredAfterDeparture(...)`, carrying
the retired live target's last issue as `finalSubscriptionIssue`, so a consumer that gives up on a stuck target can
report why without reading the flow first. When the target is retired but player
cleanup fails, `PlaybackStopResult.playerUnavailable(...)` carries the same
`finalSubscriptionIssue`; it is `null` when the player was unavailable before any
live target was retired. `SubscriptionIssue` exact values
are also non-exhaustive. Its stable `category` and
`isConfigurationOrAccessRelated` predicate support broad handling; retry and
terminal behavior depend on the surrounding subscription event.

### Pausing live TV without timeshift

Server timeshift is the way to pause live TV. Request a `timeshiftPeriod` in
`LivePlaybackOptions`; once `timeshiftState` is `LiveTimeshiftState.Available`,
pause the player and call `pauseTimeshift()` so the server holds the stream.

The SDK has no client-side timeshift store. It only keeps live media in bounded
Media3 sample queues on its way to the decoders: at most 64 MiB in total and
4,096 samples per track. While `timeshiftState` is
`LiveTimeshiftState.Unavailable`, the server keeps delivering live media into
those queues even when the player cannot advance, either because
`playWhenReady` is off or because Media3 suppresses playback (for example
transient audio-focus loss or an unsuitable audio output). How soon they fill
depends on the channel's bitrate and frame rate: a high-bitrate channel reaches
the byte bound, and a low-bitrate one reaches the sample bound.

When the queues fill while the live item still belongs to the target, the
coordinator stops the player and removes only the
Tvheadend item, which closes the subscription and releases the buffers. It
then retires the target and reports `PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED`
through the `onRecoveryRequired` callback of `createTvheadendPlaybackCoordinator`.
This is not a player error, and the player stays application-owned. Resuming
retunes to live: call `setLiveTarget()` again once playback can advance, that
is when `playWhenReady` is true and `playbackSuppressionReason` is
`PLAYBACK_SUPPRESSION_REASON_NONE`. Watch `onPlayWhenReadyChanged` and
`onPlaybackSuppressionReasonChanged`, and retune immediately if that already
holds when the callback runs. Exhaustion retires the target regardless of the
current play state, including playback resumed near the limit. Retuning while
playback still cannot advance fills the queues again. With a timeshift grant,
or outside `TvheadendPlaybackCoordinator`, overflow still fails with
"Live sample buffer limit reached".

If the application has already replaced or left the live item before exhaustion
retirement is admitted, exhaustion does not stop the player, remove items, or
report an exhaustion recovery reason. Instead the period fails with
"Live sample buffer limit reached" and the subscription closes without waiting for
another event, including when departure races an already queued exhaustion hand-off.
The subscription ends with `ConsumerFailed`, or with `Closed` when the replay of packets
buffered before period creation fails first. Returning
to that item does not restore ownership or resume its closed subscription; the
period exposes the error rather than buffering forever. As with other departed targets, the observation is
`Departed` until the next `stop()`, target change, or shutdown removes any lingering
coordinator-owned item. The application's items are preserved. To play that channel
again, explicitly install it with `setLiveTarget()` when the application wants it.

If retirement or Player cleanup cannot be confirmed, the callback reports exactly one
of the following distinct reasons. Neither case guarantees buffer release.

- **`LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED`: retirement never started.** The target may still be active: call the
  coordinator's `stop()` or `setLiveTarget()` to retry retirement. Subscription
  closure and a period error are not guaranteed.
- **`LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED`: retirement completed with incomplete Player cleanup.** The target is already
  retired, with cleanup pending. `stop()` or the next `setLiveTarget()` /
  `setRecordingTarget()` retries cleanup. The period fails and server delivery ends without another
  event (`ConsumerFailed` or `Closed`), even while cleanup remains pending.
  If the leftover Tvheadend item is still current, the Player may also
  report "Live sample buffer limit reached" as a player error; it needs no
  handling beyond the callback.

Observe `playerCleanupPending: StateFlow<Boolean>` after these callbacks,
`PlaybackStopResult.playerUnavailable(...)`, or `PlaybackTargetResult.PLAYER_UNAVAILABLE`.
When the transient Player fault clears, call `stop()` or simply retry either target setter.
The SDK retains exact source references, removes only its own items, and stops the Player
only when one of those items is current. Applications must not infer ownership from media IDs,
URIs, or the current item, and must not reconcile coordinator items themselves.
If cleanup still fails, installation returns `PLAYER_UNAVAILABLE` without creating or setting a
new source, and `stop()` returns `PlayerUnavailable`. `AlreadyStopped` means no target is
installed and no coordinator item remains to clean up. Pending items never regain progress,
watched-reporting, error, or recovery ownership. `shutdown()` retries pending cleanup once,
then abandons unconfirmed items and resets `playerCleanupPending` to false. Unconfirmed cleanup
returns `PlaybackShutdownResult.PLAYER_UNAVAILABLE`, even with no target installed. Cancellation
of `run()` also attempts cleanup once before abandoning any remainder and resetting the flag.
After shutdown or run cancellation, no later retry is possible and the coordinator no longer owns
any items: the application may clear or release its Player. Releasing it remains the normal next step.
Ancillary cleanup failures
(such as listener removal) do not change `LIVE_BUFFER_EXHAUSTED` when stopping
and item removal both succeeded. A coordinator whose run is cancelled while an
exhaustion is still pending fails that period, so the Player can report
"Live sample buffer limit reached" just before the coordinator abandons the item.

While `timeshiftState` is `Unavailable`, disable the pause control or label it
so viewers know that resuming returns to live.

### 0.4.0 app migration inventory

The enum-to-singleton replacement is an intentional provisional 0.x source and
binary break. Before an SDK release package is admitted, the in-repo Android app
must migrate these call sites from the previously staged SDK:

- `app/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt`:
  keep typed result plumbing and synthesized exact values, but use `isStarted`
  for broad success checks.
- `app/src/main/java/at/bernhardberger/tvhplayer/ui/player/VideoPlayerScreen.kt`:
  replace accepted/start comparisons with `isAccepted`/`isStarted`; add an
  `else` fallback to `SubscriptionIssue.messageResource()` so future exact
  issues use the generic playback-failure message.
- `app/src/main/java/at/bernhardberger/tvhplayer/ui/player/PlayerTimelinePresentationState.kt`:
  use `isAccepted` for broad seek acceptance.
- `app/src/test/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntimeTest.kt`,
  `app/src/test/java/at/bernhardberger/tvhplayer/ui/player/LivePlaybackStartPolicyTest.kt`,
  `app/src/test/java/at/bernhardberger/tvhplayer/ui/player/TimeshiftCommandFeedbackTest.kt`,
  and `app/src/test/java/at/bernhardberger/tvhplayer/ui/player/PlayerTimelinePresentationStateTest.kt`:
  retain exact singleton fixtures and assertions, updating broad-behavior
  assertions to predicates when they are intended to accept future values.
- `app/src/androidTest/java/at/bernhardberger/tvhplayer/playback/ForegroundPlaybackLifecycleDeviceAcceptanceTest.kt`
  and `app/src/androidTest/java/at/bernhardberger/tvhplayer/playback/TimeshiftCommandDeviceAcceptanceTest.kt`:
  use success predicates where the device contract asserts only start or
  acceptance rather than one exact SDK value.

No app source is changed by this SDK package. The qualified result type and
named constant references remain source-readable, but enum `when`, `values()`,
`valueOf()`, `name`, `ordinal`, and compiled enum switches are intentionally not
preserved.

`liveDiagnostics` conditionally exposes immutable observations for that same
current live target. Source data contains only safe adapter, mux, network,
provider, and service display names. Display text is normalized and bounded;
controls and recognizable UUID, address, locator/userinfo, MAC, authorization,
and credential-assignment forms are omitted. The SDK does not infer secrets from
otherwise ordinary human-assigned names. Frontend values use percent, dB, and dBm properties;
BER remains explicitly raw because the adapter defines no portable denominator.
Queue data contains packet/byte depth, queued-media `Duration` (also available
as explicit microseconds for Java), and B/P/I drop counts. Every unavailable
section or measurement is `null`; recordings never
imply tuner data. Replacement, stop, termination, disconnect, close, and
coordinator shutdown clear the state, and stale subscription generations cannot
publish into a newer target.

The staged Java consumer validates ordinary JVM linkage for the operation,
aggregate getter, nested result, and explicit `Continuation` signature. It does
not claim that Kotlin coroutine or `Flow` APIs are idiomatic Java ergonomics.

### Recordings

Completed recordings remain playable from the beginning when progress support
is unknown or unsupported. `RecordingPlaybackStart.RESUME` uses a positive
server position only when `recordingProgressCapability` is `SUPPORTED`; normal
completed-recording playback otherwise starts over and disables reporting.

With `@OptIn(ExperimentalTvheadendApi::class)`, recover a freshly bound recording
using a local measurement for that same recording:

```kotlin
coordinator.recoverRecordingTarget(
    binding = binding,
    position = lostPosition,
    previousDuration = lostDuration, // null if unknown or the lost target was growing
    fallbackStart = RecordingPlaybackStart.RESUME,
)
```

Positions must be finite, non-negative whole milliseconds; a supplied previous
duration must be finite, positive whole milliseconds. Zero uses `fallbackStart`.
`previousDuration` must come from completed media and must be `null` if the lost
target was growing. Passing a growing extent would trigger the completed-recording
cutoff if the freshly bound recording is now completed.
For completed media with a known previous duration, a position at or beyond
`DvrProgressPolicy.orderlyCompletionFraction` of that duration uses the fallback
before installation. With unknown duration, playback starts at zero; reaching
that cutoff on the new timeline drops only the pending seek, without a late
bookmark seek. A pending position at or beyond the completed media end is dropped
in either case. Explicit completed recovery works without server progress
support, but reporting stays disabled and fallback `RESUME` retains its support
gate. Growing recovery retains all admission gates and the 3 s / 20 s rule below,
even if the recording completes during admission; no completion cutoff applies
to its provisional extent.

Under the application's existing target-command fence, capture
`coordinator.pendingRecordingRecoveryPosition.value ?: measuredPosition` when
retrying that installed recording again. The flow is only the owed explicit
position: it clears on seek issuance, viewer seek, departure, replacement, stop
or shutdown. It does not confirm a seek landing or expose server-bookmark resumes.
While the existing progress hold lasts, checkpoints and closing reports cannot
write an earlier position. Completed recovery can remain pending beyond its
60-second progress hold. `STARTED` means installed, not seek completed, and caller
cancellation propagates even when Player work may already have been claimed.
The application retains identity matching, retry/route choice and play intent.

`RecordingPlaybackAdmission.Growing`, `GrowingRecordingFileReader`, and
`GrowingRecordingFileLease` require `@OptIn(ExperimentalTvheadendApi::class)` from
`at.bernhardberger.tvheadend.sdk.playback`. The shared admission type, completed
playback, and `DvrRepository.changes` remain stable.

The 0.27 additions `Channel.mediaKind`, `ChannelService.mediaKind`, and
`DvrRecordingFile.streams` also require `@OptIn(ExperimentalTvheadendApi::class)`.
Keep a fallback for future `ChannelMediaKind` and `SubscriptionStreamType` values.
`mediaKind` is a catalog hint, not a check of the selected playback tracks:
OTHER means classified as neither TV nor radio (possibly unscanned), whereas
UNKNOWN means insufficient or unrecognized evidence. Per-file `streams == null`
means metadata was absent; an empty list means the server explicitly supplied
no stream observations.

Growing playback is deliberately bounded, and its admission gates are the same
for `START_OVER` and `RESUME`: unknown progress support returns `NOT_READY`,
unsupported progress returns `RECORDING_PROGRESS_UNSUPPORTED`, an active
recording without exactly one usable file returns `TARGET_UNAVAILABLE`, and a
file that is not `.ts` returns `GROWING_RECORDING_DEFERRED`. For an admitted
active single-file `.ts` recording, `START_OVER` or a missing or zero saved
position plays from the beginning. `RESUME` with a positive saved server
position (exposed as `RecordingPlaybackAdmission.Growing.resumePosition`)
starts at 0:00 and seeks once, as soon as the
growing timeline below becomes seekable. On the maintained fixtures that happens
in the first probe cycle; slow file reads can delay it, so a brief start from
0:00 is expected. A saved position within 3 s of, or past, the probed recorded
extent resumes 3 s before that extent. If the timeline is not seekable within
20 s (unsupported codec, no usable PCR, lost file continuity), the resume is
abandoned and playback simply continues where it is. A viewer seek, target
replacement, stop, or coordinator close before the resume seek cancels it.
While the progress hold remains active, checkpoints, pause and stop reports do
not write a position earlier than the saved one. The hold ends when the resume
seek is issued or the resume is cancelled or abandoned, and after at most 60 s
for a completed recording whose timeline stays unseekable; that seek stays
pending until the timeline becomes seekable. After the hold ends, progress
reports follow the real position. The coordinator does not yet expose whether a resume
was applied or abandoned. Growing seek is approximate and starts
only after the maintained MPEG-TS extractor has validated MPEG-2, H.264, or HEVC
and bounded head/tail PCR reads establish the existing recorded extent. The timeline
includes content not yet consumed by playback and refreshes while playing or paused.
The probe retains one continuity-bound reader, caches the initial PCR, and checks
file size before reading the tail. Unchanged extents need no further media reads;
a confirmed final extent retires the probe. Probe failure closes its handle before
retrying. Normal playback and the probe use separate sequential readers;
Media3's PCR binary seeker resolves targets within that extent. Scheduled programme
duration does not grant seek access. Missing file-size or usable PCR evidence keeps
the recording forward-only, as do other MPEG-TS codecs.
Temporary EOF does not end playback or mark the recording watched.

### Software audio decoding

`sdk-media3` bundles no software audio decoder. `createTvheadendRenderersFactory`
prefers platform renderers and enables Media3's extension renderer mode, so an
application-supplied Media3 extension audio decoder is used only when no
platform renderer supports a format. Video extension renderers (libvpx,
libdav1d, FFmpeg video) are not installed.

An application may need one when neither platform decoding nor passthrough
covers a broadcast audio format, for example AC-3 or E-AC-3 on an Android TV
device without a platform Dolby decoder whose HDMI sink does not accept AC-3
passthrough. The same applies when passthrough is disabled through
`TvheadendAudioOutputProvider` on such a device.

To decode those formats in software, bundle the Media3 FFmpeg audio extension
(`androidx.media3.decoder.ffmpeg`) in the application. The SDK's renderers
factory then picks up its `FfmpegAudioRenderer` automatically; no SDK call is
needed. Media3 does not publish that module to Google Maven, so the application
owns:

- building the module and its native libraries as described in the Media3
  `decoder_ffmpeg` README, with the decoders it needs (for example `ac3`,
  `eac3`, and `mp3`) enabled;
- building it from the same Media3 release that `sdk-media3` resolves (see the
  [build matrix](build-matrix.md));
- FFmpeg's LGPL obligations and any codec patent licensing for its distribution.

Without a capable decoder or passthrough path, the audio track is still exposed
in the application player's `currentTracks`, but `Tracks.Group.isTrackSupported`
reports it as unsupported and the default track selector does not select it.
Playback continues with the remaining supported tracks, which may leave video
without audio. The SDK adds no track wrapper; the application can offer another
audio track or tell the user that the format cannot be played on this device.

### Ownership and shutdown

The coordinator may install and retire Tvheadend sources and listeners on the
player's application looper. It does not construct or release the player and
does not own a `MediaSession`, service, audio focus, notifications, surfaces,
autoplay, navigation, UI, or presentation policy.

The supported Android TV 1.0 topology is one active coordinator/Player pair per
session. Multiple pairs with distinct Players are not blocked, but concurrent
playback is not a support guarantee and remains subject to server, tuner and
account limits. Connection or profile teardown retires all generation-bound
work. Sharing one Player between active coordinators is unsupported: they do
not arbitrate playlist changes, progress writes or recovery. Constructing a
second coordinator neither throws for multiplicity nor evicts the first.
Android TV media picture-in-picture does not require expanding this topology.

Player state belongs to a coordinator target only while the player's current
item is the one the coordinator installed for it. If the application replaces or
clears that item, for example to play its own media, the coordinator stops
reading position, end, errors, and recovery state for the target. Pending
nonterminal progress and recovery are discarded at the item transition; an
already-started upload is not cancelled. A terminal event reached before the
replacement still reports that recording's last owned position (its known
duration for a natural end). Automatic playlist advance from a recording to an
application item also counts as natural completion, even without `STATE_ENDED`;
it can mark the recording watched. Seeking away or changing the playlist does
not count as natural completion. Repeating the coordinator's own item retains
ownership without reporting completion; live targets never gain a natural end
from an item transition.

Ownership loss is permanent for that target. After its item is replaced,
cleared, or left behind (including automatic advance), seeking or re-queuing
back to the item does not resume coordination. Set a new target to coordinate
playback again. Until the next target, `stop()`, or `shutdown()`,
`livePlaybackObservation` reports `LivePlaybackObservation.Departed` for a departed
live target. Later subscription updates or returning to its item cannot restore `Active`.
The separate `timeshiftState`, `subscriptionIssue`, and `liveDiagnostics` flows, and
timeshift commands, keep tracking the still-installed departed target until it is retired.

An application listener can replace or clear an item during its installation
transition. The target installation returns `PlaybackTargetResult.DEPARTED`
(`PlaybackTargetDisposition.DEPARTED`, also carried by `LivePlaybackTargetResult.Bound.result`),
but the target has already departed: the coordinator does not prepare the
application's item or restore the previous target over it. The normal closing
report rules apply, including protection of a pending saved resume position
even if departure prevented the resume helper from starting.

The next target, `stop()`, or `shutdown()` retires the target and removes only
its item if it lingers in a mixed playlist, without stopping or removing the
application's items. `stop()` returns a result with `PlaybackStopOutcome.RETIRED_AFTER_DEPARTURE`
when retirement leaves application playback alone. If the application returned to the
lingering coordinator item and it is current at retirement, retirement stops the Player
and removes that item, and `stop()` returns a result with `PlaybackStopOutcome.STOPPED` instead.
This does not reclaim ownership for reporting. Both outcomes carry the
final subscription issue. If no terminal has already been reported or captured, a
replaced or cleared recording reports one closing `ORDERLY` progress update
using the last position the coordinator observed while it owned the item. This
historical position never marks it watched, even near the end; no closing
update is made if no owned snapshot exists. The application's position is
never used, and a previously reported terminal is not reported again.
Metadata-only recording item updates retain ownership through the per-install recording URI; reusing
only the media id with another URI does not.

When a live target stays stuck in buffering, recovery may disable the audio
track type in the player's track selection parameters once. It restores only a
disable it applied itself: when playback becomes ready or idle, when the next
live target begins, at a timeline or item transition away from the target, or when the
target is retired. Audio that the application disabled stays disabled, and if
the application re-enables audio while the recovery disable is in effect, that
disable is no longer recovery's to restore. Setting the flag to the value it
already has does not change the player, so disabling audio while the recovery
disable is in effect does not take that disable over.

When the owner cannot fit its work in one suspending block, use the lower-level
lifetime handle and shut down in ownership order:

```kotlin
val lifetime = coordinator.launchIn(applicationScope)
// Install targets and launch application work.
handlePlaybackShutdownResult(lifetime.shutdown(2.seconds))
lifetime.join() // safe to repeat
session.shutdown()
player.release()
```

The coordinator shutdown timeout must be finite, non-negative, and at most ten
seconds. It may drain one pending progress report and returns a typed
`PlaybackShutdownResult` after terminal cleanup. Caller cancellation remains a
`CancellationException`, but cleanup still completes before it propagates. The
application still shuts down the session and releases its own player.

Applications using `sdk-playback` directly own their custom media adapter and
must handle its typed subscription state machine, generation termination, seek,
and server-operation outcomes. `sdk-media3` is the supported high-level Android
boundary; the SDK does not provide URI factories or application-level track
wrappers.

`ActiveSubscription.close()` returns `SubscriptionCloseResult.CLOSED` for a full
drain, `CLEANUP_FAILED` for unsubscribe or collection cleanup failure,
`CONSUMER_DRAIN_TIMED_OUT` when teardown cancelled consumer code after its grace
period, or `CLEANUP_FAILED_AND_CONSUMER_DRAIN_TIMED_OUT` when both occurred.
Caller cancellation still propagates. `SubscriptionDiagnostics.consumerDrainTimedOut`
also exposes that cancellation after lifecycle teardown without a subscription close caller.
Media3 treats all four outcomes as local delivery retirement, never as proof of
Player cleanup and never as an instruction to retry a closed subscription.

`SubscriptionTermination.SUBSCRIBE_REJECTED` marks a rejected startup. If it
arrives before the subscribe result, the state machine waits up to five seconds
for the typed refusal, preserving reasons such as `CONNECTION_LIMIT` and
`ACCESS_DENIED`. Without a result it ends with
`OperationFailed(SERVER_REJECTED)`, cancels and joins the request, and unsubscribes
once. Caller cancellation and owner close take precedence over that wait.

## Add Android integration

### Profiles

The Android-free `ServerProfileStore` contract is implemented by
`TvheadendServerProfileStore`, which atomically persists one selected profile
for one application process. `loadProfile()` returns `Missing`, `Unavailable`, or an
`Available` result containing an opaque connectable `ServerProfile` plus
non-secret endpoint fields. It does not expose password fields:

```kotlin
when (val stored = profileStore.loadProfile()) {
    ServerProfileReadResult.Missing -> Unit
    ServerProfileReadResult.Unavailable -> Unit
    is ServerProfileReadResult.Available -> session.connect(stored.profile)
}
```

`storeAnonymous` and `storePassword` return the locally normalized `Available`
profile after persistence, so it can be passed explicitly to `session.connect`
without a readback. `clearProfile` returns authoritative `Missing` state after
local removal. Any mutation can return typed `Unavailable` state. Success says
nothing about server reachability, authentication, or session readiness.
Anonymous profiles do not use the Android Keystore. Password profiles are
encrypted at rest with endpoint-bound associated data. JVM consumer tests can
use `FakeServerProfileStore` for positive and unavailable behavior without an
Android `Context`.

`loadProfileForEditing()` additionally distinguishes `Anonymous` and
`Password`. The password result contains immutable plaintext username and
password strings that cannot be zeroed. Keep it only in private memory while an
active secure edit surface needs it, then drop every reference. Never serialize
it, save it in instance state, log it, or use it as diagnostic data. The store
does not coordinate Android multi-process access.

#### Exclude the profile from backups

`TvheadendServerProfileStore` uses one DataStore file,
`files/datastore/tvheadend_sdk_credentials.preferences_pb`. Android Auto Backup
and device-to-device transfer include it by default, together with its
plaintext endpoint fields. The Keystore key that encrypts password profiles is
never restored, so a restored password profile loads as `Unavailable` and the
user must enter it again. Exclude the file in the application's backup rules so
a restore starts from `Missing` instead. Add the `<exclude>` elements to the
application's existing rules files rather than replacing them.

Apps that target API 31 or higher use `dataExtractionRules` on Android 12 and
later, for example in `res/xml/data_extraction_rules.xml`:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="datastore/tvheadend_sdk_credentials.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="datastore/tvheadend_sdk_credentials.preferences_pb" />
    </device-transfer>
</data-extraction-rules>
```

On earlier Android versions, and for apps that target a lower API level,
`fullBackupContent` applies, for example in `res/xml/backup_rules.xml`:

```xml
<full-backup-content>
    <exclude domain="file" path="datastore/tvheadend_sdk_credentials.preferences_pb" />
</full-backup-content>
```

Reference both from the `<application>` element with
`android:dataExtractionRules="@xml/data_extraction_rules"` and
`android:fullBackupContent="@xml/backup_rules"`. On Android 12 and later,
`android:allowBackup="false"` does not stop device-to-device transfer, so keep
the `<device-transfer>` rule even then.

A store file that cannot be parsed is replaced on first access and reads as
`Missing`; a record that parses but cannot be used, such as one whose Keystore
key is gone, reads as `Unavailable`.

### Discovery and connectivity

Collect `TvheadendDiscovery(context).state` while LAN discovery is needed. It is
a cold flow: collection owns Android NSD registration, resolution, and cleanup.
`TvheadendDiscoveryState.Discovering` carries the current immutable server
snapshot. `Unavailable` reports only `PERMISSION_DENIED` or `START_FAILED` and
ends that collection.

`TvheadendConnectivity(context).status` is another cold flow with `UNKNOWN`,
`AVAILABLE`, and `UNAVAILABLE` states. Running
`retrySessionWhenAvailable(session)` in an application-owned coroutine
interrupts the session's retry delay when Android reports a default network;
it runs until caller cancellation. The session remains the owner of connection
and retry policy, and profile-change failures remain application decisions.

### Authenticated artwork

Register the SDK's Coil 3 memory keyer and fetcher together on an
application-owned image loader:

```kotlin
val imageLoader = ImageLoader.Builder(context)
    .components {
        addTvheadendArtwork()
    }
    .build()

val observed = session.observation.value
val currentSession = observed.currentSession ?: return
val icon = observed.channel(channelId)?.icon ?: return
val artwork = TvheadendArtwork.create(
    session = session,
    currentSession = currentSession,
    id = icon,
)
```

`Channel.icon` and `ChannelTag.icon` are typed `ArtworkId` values; the SDK
already drops external URLs and malformed selectors. For other image-cache
selector strings, such as `EpgEvent.image`, use `ArtworkId.parse`, which accepts
only positive `imagecache/<id>` selectors (a leading `/` is tolerated) and
returns `null` for absent, external, malformed, or unsupported values. The opaque model
binds the authenticated load to the captured session generation. Tvheadend
requires recorder access for this file API; denied loads fail safely.

With a cache policy, the model captures `ArtworkLoader.cacheKey`, an opaque
restart-stable digest of the profile namespace and artwork identity. Without a
policy, keys remain process-local and generation-scoped. Keys never contain raw
connection details or selectors and must not be logged. A model keeps its key
when its observation retires, but a new fetch rejects the retired proof. Coil
memory hits may reuse already-decoded images; retention and `clear()` govern SDK
disk bytes, not application-owned decoded memory. Fetches return stream sources
without Coil disk identities. Do not configure Coil disk caching for this
integration; the SDK policy owns persistence. Catch
`TvheadendArtworkLoadException` and inspect its typed `failure`; do not parse
exception text. Coil owns decoding and closes successful image sources. This
component is not a URI factory or a generic image cache.

Experimental `TvheadendArtwork.cached(session, id)` shows logos from the SDK's
persistent artwork cache before `Ready` and while offline. It returns `null`
without a cache policy or an active profile, and its fetches never reach the
server. The model is bound to the profile active at creation: once another
profile is active it fails with `OBSERVATION_EXPIRED` (or `CONNECTION_CHANGED`
when the switch races the read) and never shows that profile's bytes, even for
an equal `ArtworkId`. A missing, expired or cleared entry fails with
`NOT_READY`. Its memory key equals the `create` model's key for the same
profile and artwork, so switching to `create` once a current session exists
reuses the decoded image without a second load:

```kotlin
val artwork = observed.currentSession
    ?.let { TvheadendArtwork.create(session, it, icon) }
    ?: TvheadendArtwork.cached(session, icon)
```

Without Coil, `ArtworkLoader.cachedArtwork` and `loadCachedArtwork` expose the
same disk-only path; custom loaders default to `null` and `NOT_SUPPORTED`.

## Test a consumer

Add `sdk-testing` with `testImplementation`. `FakeTvheadendSession` implements
the JVM session boundary with scriptable observations, commands, repositories,
typed failures, and recorded calls. Its round trips and bindings enforce
generation authority: a proof stays valid while republication retains it and
expires on `replaceGeneration`, `retire`, or use with another session. Its
lifecycle commands only record calls and return scripted results; `connect`,
`retry`, `disconnect`, and `shutdown` publish no state and retire no proof, so
drive those transitions with `publish`, `replaceGeneration`, and `retire`. Opt into
`FakePlaybackApi` only when scripting successful playback bindings.
Observations supplied to its constructor or publication methods are republished
under the fake's authority, so capture a fresh proof with
`captureCurrentSession()`. Successful fake bindings validate and track target
identity, but their open operations deliberately return typed unavailable or
not-ready results instead of forging gateway resources. `FakeSessionObservation`
remains a narrow observation-shape fake; it does not model session authority.
`ScriptedSubscriptionConnection` and packet fixtures support lower-level tests.

These are focused SDK-boundary building blocks, not a UI or application
architecture framework. Applications remain responsible for faking their own
SDK-facing abstraction where that is the appropriate test boundary.

## Malformed responses

A malformed response means the SDK could not interpret or validate the reply;
it does **not** establish server rejection or transport loss. The server may
already have applied the command. Never blindly retry non-idempotent requests
(such as DVR creation or relative seek); reconcile authoritative state first.

| API | Malformed-response result |
|---|---|
| DVR mutations | `DvrMutationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true)` after write dispatch; `false` for the entry config lookup |
| DVR progress | `DvrProgressResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true)` |
| Cutpoints, EPG search, stream-profile discovery | Each result's `Failed(TvheadendFailure.MALFORMED_RESPONSE)` |
| Artwork | `ArtworkLoadResult.Unavailable(TvheadendFailure.MALFORMED_RESPONSE)` |
| Recording-file operations | `RecordingFileResult.Failed(TvheadendFailure.MALFORMED_RESPONSE)` |
| Initial synchronization | `SessionFailure.synchronizationFailed(TvheadendFailure.MALFORMED_RESPONSE)`, with `EXPLICIT_RETRY` rather than automatic backoff |
| Low-level subscription commands | `SubscriptionOperationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE)`; a failed startup carries the same failure in `OperationFailed` |
| Media3 timeshift commands | `TimeshiftCommandResult.MALFORMED_RESPONSE`, with `UNCONFIRMED` disposition and an uncertain outcome |

A malformed seek acknowledgement also invalidates uncertain packet evidence;
the SDK does not replay packets withheld for that command. Ordinary server
refusals remain distinct typed classifications. Handle `Failed` in each sealed
result, then compare known `TvheadendFailure` values with a fallback branch.

## Typed limits and privacy

Public suspending server round trips return typed outcomes. Handle the complete
sealed result for the operation being called rather than parsing text or
retrying every failure. Caller cancellation always propagates as
`CancellationException`.

SDK diagnostics, result rendering, and `toString()` implementations redact
credentials, endpoints, paths, hostnames, usernames, raw server errors, and
subscription IDs. Treat model fields such as profile-edit credentials and DVR
paths as sensitive application data: do not place them in logs, diagnostics,
cache keys, or error messages.
