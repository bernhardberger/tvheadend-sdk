# Consumer API guide

This guide covers the application-facing API of the independently maintained
TVHeadend Kotlin SDK. It is not official TVHeadend software. The SDK is GPLv3
and links the GPLv3 HTSP protocol library.

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
Android publications require API 24 or newer.

## Own one session

`createTvheadendSession()` returns the process-wide session owner. Repeated
calls return the same instance until terminal `shutdown()` completes, so an
application should share it rather than construct feature-specific sessions.
Exactly one server profile and protocol generation can be active.

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
| `dvrEntryForEvent(eventId)` | The unique recording linked to an event |
| `epgEventForDvrEntry(entryId)` | The unique event linked to a recording |

`Channel.tagIds` and `ChannelTag.channelIds` expose catalog membership. Entity
lists are immutable and preserve SDK catalog order. Presentation filtering,
sorting, grouping, and UI policy are not SDK APIs.

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
    EpgSearchResult.ObservationExpired -> Unit
    EpgSearchResult.InvalidQuery -> Unit
    EpgSearchResult.AccessDenied -> Unit
    EpgSearchResult.ConnectionLimit -> Unit
    EpgSearchResult.Timeout -> Unit
    EpgSearchResult.TransportUnavailable -> Unit
    EpgSearchResult.ConnectionChanged -> Unit
    EpgSearchResult.NotSupported -> Unit
}
```

`originatingSession` is the exact proof that admitted the successful search. It
records provenance only; the proof may already be retired when the result is
observed. Check current state or wait for a replacement before a new operation.

Search does not read or mutate retained coverage. `InvalidQuery` also covers an
unusable server payload and the server's generic rejection of inaccessible
channel or tag filters. Search durations, when supplied, must be finite,
non-negative whole seconds; content type is an unsigned eight-bit genre code.

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

Covered results carry the exact immutable `SessionObservation` that established
the answer; use that observation rather than recapturing a potentially newer
one. `CoveredEmpty` proves the query succeeded without retained programmes.
`Ineligible` covers an unknown channel, a boundary outside policy, or missing
server query capability.

The default `EpgCoveragePolicy` keeps a 24-hour future horizon and at most
100,000 events. A custom policy supplied to `createTvheadendSession(policy)` can
request 24 hours through 7 days and 1 through 250,000 retained events. The
policy applies only when that call creates a fresh process-wide session owner.

## Read and mutate DVR state

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
    DvrMutationResult.NotReady -> Unit
    DvrMutationResult.ObservationExpired -> Unit
    DvrMutationResult.ServerRejected -> Unit
    DvrMutationResult.AccessDenied -> Unit
    DvrMutationResult.ConnectionLimit -> Unit
    DvrMutationResult.Timeout -> Unit
    DvrMutationResult.TransportUnavailable -> Unit
    DvrMutationResult.NotSupported -> Unit
}
```

`Confirmed` means matching authoritative metadata was published.
`AcceptedButUnconfirmed` means the server accepted the command but confirmation
did not arrive before the deadline; later observation data remains
authoritative. Mutations and progress are admitted only for a ready generation.
Cancellation remains caller-owned and is not converted into a failure result.

Cutpoints and progress belong to an observation-bound
`PlaybackBinding.Recording`, not to the mutation repository. `cutpoints()`
returns `DvrCutpointsResult`, including `Available`, readiness and expiration
states, safe server failures, and `NotSupported`. Cutpoint actions are metadata;
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
retains the exact `PlaybackTargetResult`, including `STARTED`, coordinator
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
`STARTED`, coordinator lifecycle failures, `NOT_READY`,
`RECORDING_PROGRESS_UNSUPPORTED`, `TARGET_UNAVAILABLE`, the two typed growing
recording limitations, or `PLAYER_UNAVAILABLE`. Handle the result before
assuming Media3 accepted the target. Exact values are SDK-owned singletons, not
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

`seekTimeshift(offset)` seeks by a signed relative duration. `returnToLive()`
requests the bounded near-live position, not a separate exact-live mode.
`pauseTimeshift()` and `resumeTimeshift()` control server delivery only; normal
Media3 play and pause remain application-owned. Every command returns
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
publication. It emits `Active` when any of those components changes and resets
to `NoTarget` as one retirement transition before stale target callbacks can
publish again. `timeshiftState`, `subscriptionIssue`, and `liveDiagnostics`
remain available for consumers interested in only one component; independently
collecting those flows does not create an atomic aggregate snapshot.

`subscriptionIssue` exposes only the current live target's canonical
`SubscriptionIssue`. Unknown or localized server values map to `UNKNOWN`; raw
server text is not exposed. The exact no-input status maps to `NO_INPUT` unless
a conflicting known canonical error is present. The state clears when the
target or lifecycle no longer owns that issue. `SubscriptionIssue` exact values
are also non-exhaustive. Its stable `category` and
`isConfigurationOrAccessRelated` predicate support broad handling; retry and
terminal behavior depend on the surrounding subscription event.

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

Growing playback is deliberately bounded. It requires supported progress, one
stable `.ts` file, and explicit `RecordingPlaybackStart.START_OVER`. `RESUME`
returns `GROWING_RECORDING_RESUME_UNSUPPORTED`; active recordings outside that
path return `GROWING_RECORDING_DEFERRED`. Growing seek is approximate and starts
only after the maintained MPEG-TS extractor has validated MPEG-2, H.264, or HEVC
and indexed parsed keyframes. Other MPEG-TS codecs remain forward-only.
Temporary EOF does not end playback or mark the recording watched.

### Ownership and shutdown

The coordinator may install and retire TVHeadend sources and listeners on the
player's application looper. It does not construct or release the player and
does not own a `MediaSession`, service, audio focus, notifications, surfaces,
autoplay, navigation, UI, or presentation policy.

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
val channel = observed.channel(channelId) ?: return
val artwork = TvheadendArtwork.create(
    session = session,
    currentSession = currentSession,
    source = channel.icon,
)
```

`TvheadendArtwork.create` accepts only HTSP `imagecache/` selectors and returns
`null` for absent, external, malformed, or unsupported values. The opaque model
binds the authenticated load to the captured session generation. TVHeadend
requires recorder access for this file API; denied loads fail safely.

The SDK derives an opaque process-local memory key from the current generation
and artwork identity. The key contains no selector, endpoint, hostname,
username, path, ticket, credential, or stable cross-process history. Retired
proofs do not produce keys, and replacement generations cannot reuse old
entries. Fetches return stream sources without disk identities, so this
component does not authorize persistent authenticated artwork caching. Catch
`TvheadendArtworkLoadException` and inspect its typed `failure`; do not parse
exception text. Coil owns decoding and closes successful image sources. This
component is not a URI factory or a generic image cache.

## Test a consumer

Add `sdk-testing` with `testImplementation`. `FakeTvheadendSession` implements
the JVM session boundary with scriptable observations, commands, repositories,
typed failures, and recorded calls. It enforces production generation authority,
including proof retention, replacement, and cross-session rejection. Opt into
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

## Typed limits and privacy

Public suspending server round trips return typed outcomes. Handle the complete
sealed result or enum for the operation being called rather than parsing text or
retrying every failure. Caller cancellation always propagates as
`CancellationException`.

SDK diagnostics, result rendering, and `toString()` implementations redact
credentials, endpoints, paths, hostnames, usernames, raw server errors, and
subscription IDs. Treat model fields such as profile-edit credentials and DVR
paths as sensitive application data: do not place them in logs, diagnostics,
cache keys, or error messages.
