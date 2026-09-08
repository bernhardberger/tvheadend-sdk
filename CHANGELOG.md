# Changelog

## [0.10.1]

Live Media3 periods accept equal, non-identical track groups carried by retained
Media3 overrides across restart or retune. Selection validates an available
group containing the selected single track at index zero. Retained sample streams
must belong to the selected current queue; switching tracks or periods resets the
stream instead of reusing another queue. Public APIs, HTSP 0.9.0, Media3 1.11.0,
native binaries, cancellation, and segment/seek contracts are unchanged.

Owning JVM regressions cover current/equal-fresh/unavailable groups, invalid
indices, retention, switching, deselection, and interruption. Offline real
ExoPlayer tests carry a stored override across unchanged and changed restart
tracks and a new-channel retune without a Player error, in playing and paused states. These establish
the SDK selector path, not the Player application's route or physical audio.
See [selection contract and evidence](docs/media3-selection-contract.md).

## [0.10.0]

Live playback now surfaces state-only subscription termination to Media3 instead
of silently stalling. Transport cancellation settles an unresolved seek without
replaying uncertain packets, and content-seek cancellation reaches its caller
instead of becoming an ordinary unavailable result. Operation cancellation through
the playback coordinator retires that coordinator owner.

Failed target replacement restores the recording's captured position rather than
restarting its source at zero. If restoration also fails, final progress uses the
pre-replacement snapshot and does not mistake partially replaced player state for
a natural recording end.

Live preparation waits for one initialized stream in each advertised supported
audio/video category, then allows unresolved alternatives up to one second for
format discovery. It prepares immediately when all supported formats are ready.
Alternatives still unresolved at the deadline are omitted for that period, so a
silent secondary track cannot block preparation indefinitely. Output retirement
and playback-side queries share the reader lock.

EPG capacity rejection now settles waiting single and batch acquisition requests
without partial retention updates or a permanent capability-denial latch. Existing
cooldown, stale-query authority, observation expiry, and cancellation rules remain.

The SDK now resolves published HTSP 0.9.0, retaining stricter reply-sequence
validation and safer JSON diagnostics. Stop/start and repeated-start stream
sequences replace Media3 readers and track groups in a new period on the same
subscription and application-owned Player. Stop fences the old segment without
EOS or unsubscribe; missing restart metadata has a five-second deadline. An
unresolved seek intersecting restart fails closed because protocol skip
acknowledgements carry no segment correlation.

This minor release changes the provisional infrastructure API: `SubscriptionTracks`
has a public constructor for boundary fakes, `ActiveSubscription.seek` gains an
expected-tracks overload, and exhaustive seek outcomes gain `SegmentUnavailable`.
Infrastructure consumers must rebuild and handle that outcome. Timeshift content
targets are segment-scoped; consumers combining sampled content and history must
check `TimeshiftTimeline.describesSameSegment`, not just `describesSameSubscription`.
`TimeshiftTestFixture.restartSegment()` models replacement without changing
transport identity. See [repair behavior and evidence](docs/audit-defect-repairs.md)
and the [stream restart contract](docs/stream-restart-contract.md). Host regressions
cover restart ownership and stale-coordinate fences; real ExoPlayer restart tests
pass on the configured Android device with recorded MPEG-audio and AC-3 fixtures.
This verifies audio track reselection and play/pause intent, not live-server video
restart or interruption-free playback across all codecs.

## [0.9.1]

Persistent cache namespaces now encode field boundaries unambiguously and use a
new namespace version. Legacy metadata and artwork are not restored or migrated
because the old namespace could identify different profiles. Restore removes
recognized legacy SDK namespaces without touching current namespaces or unrelated
application data. Opaque `ArtworkLoader.cacheKey` values change once on upgrade;
application-owned artwork caches miss once and retain their own eviction policy.
Cancelling a clear no longer leaves a connected session without a metadata writer.

DVR snapshots are reused until accepted DVR metadata changes. Known-channel
pointer updates no longer reconstruct an unchanged EPG snapshot. Observation
event lookups use a lazy snapshot-owned ID index; Now/Next lookups inspect only
the channel's retained events. Empty-DVR programme lookups skip EPG work entirely.
Public signatures, duplicate-ID ambiguity, ordering, retention and generation
authority are unchanged. See [cache and metadata repair evidence](docs/cache-metadata-repairs.md)
for correctness coverage, structural cost evidence and remaining limitations.

## [0.9.0]

Timeshift timelines expose immutable schedule-grade `TimeshiftWallClockMapping`
`Estimate` or `Unavailable` state, replacing the unavailable-only enum. Estimates
associate observed live buffer ends with existing server-time observations and
monotonic elapsed time, without extra polling. Playback samples carry matching
history; retaining a mapping during preview keeps labels and media targets stable.
Reader pause preserves timing evidence. Invalid stream continuity disables new
estimates until replacement. These are approximations with no UTC error bound;
seek coordinates and history validation are unchanged. The public host fixture
can script an estimated live-edge time. See [programme-time estimates](docs/programme-time-estimates.md)
for the consumer route and limitations.
The infrastructure `SubscriptionEvent.Timeshift` constructor also changes its
binary signature; rebuild infrastructure consumers against 0.9.0.

## [0.8.0]

Sessions can persist catalog and guide metadata between processes. Passing a
`MetadataCachePolicy` to `createTvheadendSession(epgCoveragePolicy, cachePolicy)`
makes the SDK store the published `ChannelCatalog` and `EpgSnapshot` per server
namespace (a hash of host, port and username) under the policy root, and seed
them as `Stale` repository states before the next connection attempt, so a cold
start can browse channels before the HTSP initial sync completes. The catalog is
written on every change and the guide is coalesced to one write per minute plus
a flush on disconnect. Files older than `metadataRetention` or that fail to
decode are deleted. Nothing served from disk is ever published as `CURRENT`.
`TvheadendSession.cache` exposes `SessionCache` with `statistics` and `clear()`;
sessions created without a policy report empty statistics. `FakeTvheadendSession`
gains `FakeSessionCache`.

Artwork bytes now persist under the same namespace. Hits avoid HTSP file loads;
entries expire after `artworkRetention` (30 days by default), with root-wide LRU
eviction against `artworkMaxBytes` (64 MiB by default). Statistics include encoded
artwork bytes and entry count, and `clear()` deletes artwork in every namespace
without allowing an already-pending fetch to repopulate it. Corrupt files and
indexes are discarded. In `sdk-android`, Coil models capture the opaque
`ArtworkLoader.cacheKey` derived from namespace and image ID for consistent
memory identity across sessions. Restart persistence comes from the SDK byte
store, not Coil memory. Without a policy keys remain process-local.
Fetches reject retired observations; Coil memory hits can reuse decoded content.
Persistence belongs to the SDK, not Coil's disk cache.

## [0.7.0]

Guide traffic no longer starves the consuming process. Every HTSP EPG add,
update or delete used to rebuild the full `EpgSnapshot`, including a per-channel
coverage scan over all retained events, and publish a new `SessionObservation`
whose equality walked the whole event list again in every state flow and UI
comparison. Profiling a small TV showed most of the process busy in that path
while the player idled. The session now reduces a burst of guide updates and
publishes once per drained burst (at least every 512 events under a continuous
stream), the snapshot builds coverage in a single pass and is reused unchanged
until retained events actually change, and `EpgSnapshot` compares through a
content hash computed once on the producing thread. Structural equality is
unchanged: equal snapshots remain equal.

Live recovery no longer treats an ordinary tune as a stall. A target that has
not yet presented selected audio is still being tuned, descrambled and
delivered, so it now runs on a separate `preparationDurationMillis` budget
(20 seconds by default) instead of the six-second stuck-buffering budget. Since
`0.6.0` that shared budget escalated every tune slower than six seconds to
`AUDIO_RECOVERY_EXHAUSTED`, and each replacement target restarted the same
timer, so a slow server produced an unbounded retune loop. The short budget
still applies once a target has presented audio, and a new target resets that
observation.

`TimeshiftPlaybackPosition.Estimate` now carries the `timeline` observed in the
same sample as its content coordinate, and `TimeshiftTimeline` gains
`describesSameSubscription`. A consumer that samples a position and reads
history separately can be interrupted by subscription replacement between the
two reads; combining those coordinates reports a false distance behind live and
can authorise a seek on the successor derived from its predecessor. Equality
alone cannot separate that case from ordinary edge advancement.

Artwork loads now hold at most three HTSP file handles open per connection.
Each load keeps a file open across `fileOpen`, chunked `fileRead` and
`fileClose` round trips; an unbounded burst, such as a channel list scrolling
picons, exhausted the server's per-connection file budget and turned every
further load into a rejection. Additional loads wait for a free handle instead.

`LiveSubscriptionDiagnostics` gains `clientDroppedPacketCount`, the number of
packets the client's own protocol queue evicted because the consumer fell behind.
Server-side frame drops were already reported in `queue`; without the client
count a consumer could not tell a starved server from a slow client.

The added `PlaybackRecoveryPolicy`, `Estimate` and `LiveSubscriptionDiagnostics` constructor fields are
intentional provisional ABI changes from `0.6.1`. HTSP remains pinned to
`0.7.0`; no protocol dependency substitution or new minimum TVHeadend version is
introduced.

## [0.6.1]

Add the SDK-owned `TimeshiftTestFixture` for application host tests of opaque
timeshift targets, displayed-position estimates and command results. Tests can
advance history, expire targets, replace subscriptions and hold dispatch replies
without reflection, real players or duplicated app timing policy. Production
constructors remain opaque; this is a backward-compatible consumer-testing fix.

## [0.6.0]

Live timeshift observations expose an absolute observed history and opaque
subscription-scoped content targets. A selection keeps its content coordinate
as the edge advances; execution rejects expired targets and subscription
replacement without clamping or sending the target to a successor.

The coordinator maps sampled Media3 position through bounded packet-timestamp
segments, preserving queued pre-seek content through pause and timestamp rebasing.
Mapping is explicitly estimated or unavailable, never inferred from server-reader
shift. Seek results carry a request-correlated reached reader coordinate when
available, which is not proof of displayed content. Programme wall-clock mapping
remains unavailable because the inspected packet/status path supplies no UTC anchor.
Capacity grants remain separate from observed seekable history.

The added packet-coordinate and timeshift-state constructor fields are intentional
provisional ABI changes from `0.5.0`. HTSP remains pinned to `0.7.0`; no protocol
dependency substitution or new minimum TVHeadend version is introduced.

## [0.5.0]

This provisional minor line exposes immutable, current-target live diagnostics
for source display metadata, frontend state and measurements, and server queue
depth, media span, and packet drops. Values are normalized, bounded, redacted,
generation-fenced, and cleared when the live subscription or coordinator target
ends. The exact no-input status maps to `NO_INPUT` unless a conflicting known
canonical error is present.

Playback target, timeshift command, and live-subscription issue outcomes now use
non-exhaustive SDK-owned singleton values instead of public enums. Stable
dispositions, non-exclusive categories, and direct predicates let Kotlin and
Java applications handle success, transient, terminal, unsupported,
configuration/access, and uncertain outcomes without becoming exhaustive over
future exact SDK values. Existing semantic distinctions, typed control flow,
cancellation propagation, and redacted fixed rendering are preserved.

Session observations now expose current-generation checks and cancellable waits,
and successful EPG search and stream-profile results retain their originating
session proof. Channel, EPG, and DVR snapshots expose explicit retained-metadata
authority plus display-safe retained data while a replacement generation is
synchronizing.

The profile-store contract now lives in `sdk-core`, with typed, redacted read and
mutation results implemented by the Android store and its JVM fake. Session and
operation failures expose a stable recovery disposition so applications can
distinguish automatic backoff, explicit retry, profile correction, and terminal
failures without interpreting transport details.

EPG coverage can be acquired for a batch of channel identifiers in one
generation-bound operation. Ordered per-channel settlements distinguish covered
channels from absent, rejected, or expired targets without turning partial
success into an aggregate exception.

The Media3 playback coordinator adds structured `launchIn` and `withLifetime`
entry points, a generation-fenced bind-and-install live workflow, and one
coherent live observation combining timeshift state, subscription issue, and
diagnostics. Applications still own the `Player` and all presentation and
service policy.

`sdk-testing` adds a composable generation-aware `FakeTvheadendSession` with
scriptable repositories, profile storage, artwork, playback bindings, typed
failures, and recorded calls for Kotlin and Java consumers.

The playback outcome replacement, profile-store move, and originating-session
result changes are intentional source and binary breaks from the provisional
`0.4.0` API; legacy enum machinery and compatibility shims are not retained.
The published set remains the same five SDK artifacts, and production dependency
identities are unchanged, including HTSP `0.7.0`. Compatibility remains
provisional during the major-zero line, and local or CI verification does not
establish Maven Central availability.

## [0.4.0]

This provisional feature release adds generation-bound EPG search with typed
requests and outcomes. Applications can constrain searches by channel, tag,
content type, language, and duration without receiving raw server errors, and
the captured current-session observation prevents a delayed result from being
accepted after its connection generation expires.

Applications can also configure the session's future EPG horizon and retained
event limit through `EpgCoveragePolicy`. Initial synchronization remains
bounded, while explicit coverage acquisition can extend the retained horizon
for the admitted generation.

The `sdk-core` ABI changes from `0.3.4` are additive: the policy, search request
and result types, repository search operation, and policy-taking session factory
overload are new, while existing declarations remain unchanged. The other JVM
module ABI dumps are unchanged. Compatibility remains provisional during the
major-zero line, and local or CI verification does not establish Maven Central
availability.

## [0.3.4]

This provisional patch release carries forward the timeshift speed-command fix
from the unpublished `0.3.3` development release. Pause and resume round trips
run on the subscription-owned dispatcher while preserving caller cancellation
and typed transport-failure outcomes.

Android settings screens can now use
`TvheadendServerProfileStore.loadProfileForEditing()` to distinguish missing,
unavailable, anonymous, and password profiles. Anonymous and password results
expose the normalized endpoint; only the password result exposes the exact
normalized username and password. These dedicated edit results have redacted
rendering and identity semantics rather than generated value, copy,
destructuring, or serialization APIs. Callers must keep immutable plaintext only
in private memory while an active secure edit surface needs it, then drop every
reference.

Connection reads remain opaque, password fields remain encrypted at rest with
endpoint-bound associated data, and storage, decryption, and cancellation still
fail closed. Existing APIs remain source- and binary-compatible, but compatibility
is provisional during the major-zero line. Local or CI verification does not
establish Maven Central availability.

## [0.3.3]

This provisional patch release fixes timeshift speed commands, including pause
and resume, when they are invoked from an application-owned coroutine context.
The subscription now runs the HTSP speed round trip on its owned dispatcher
rather than inheriting the caller's dispatcher.

Caller cancellation still propagates, and transport failures retain their
typed `TRANSPORT_UNAVAILABLE` outcome. Subscription admission, generation
fencing, and speed-result mapping are otherwise unchanged. The public API and
ABI are unchanged from `0.3.2`. Compatibility remains provisional during the
major-zero line, and local or CI verification does not establish Maven Central
availability.

## [0.3.2]

This provisional patch release fixes Media3 recovery for a live target that
remains buffering before any audio track is selected. The original 6,000 ms
initial recovery deadline now remains active while tracks are loading and emits
exactly one `AUDIO_RECOVERY_EXHAUSTED` request if the target is still buffering
without selected audio when that deadline expires.

If selected audio is present at the initial deadline, the existing two-stage
recovery still disables it and waits through a second 6,000 ms deadline before
escalating. Ready, idle, target replacement, and close continue to cancel
pending recovery, while stale timer callbacks remain fenced. The public API and
ABI are unchanged from `0.3.1`. Compatibility remains provisional during the
major-zero line, and local or CI verification does not establish Maven Central
availability.

## [0.3.1]

This provisional patch release fixes a Media3 coordinator completion defect.
When target installation already owns the application-owned `Player` looper,
the SDK now performs the guarded player operation inline instead of posting
back to the same looper. `TvheadendPlaybackCoordinator.setLiveTarget` can
therefore return its typed result after installing the source rather than
remain suspended while playback proceeds.

Queued operations retain their existing cancellation and rejected-post
behavior. The public API and ABI are unchanged from `0.3.0`. Compatibility
remains provisional during the major-zero line, and local or CI verification
does not establish Maven Central availability.

## [0.3.0]

This provisional minor release intentionally replaces the SDK's public session
and playback authority. `TvheadendSession.observation` now publishes lifecycle,
channel, EPG, DVR, capability, and freshness state together in one immutable
`SessionObservation`. Its `CurrentSessionObservation` is the generation-owned
capability for current operations, while retained stale data remains selectable
without becoming current again.

EPG acquisition, DVR mutations, recording progress and cutpoints, stream
profile discovery, and authenticated artwork now use the captured current
session. Delayed operations return typed expiration outcomes instead of
resolving colliding identifiers against a later connection generation.

Live and recording playback now start from an observation-bound
`PlaybackBinding`. The binding carries the exact generation and target identity
through queued coordinator work, delayed opens, extractor reopens, resume,
cutpoints, and progress reporting. Recording bindings also retain the DVR
incarnation, and completed recordings remain playable from the beginning when
progress support is unavailable.

Applications must migrate from the removed independent session flows, bare-ID
operations, and public raw playback routes to the aggregate observation and
binding-based API. This remains a major-zero release: source, binary, and
behavioral compatibility are provisional, and local or CI verification does
not establish Maven Central availability.

## [0.2.0]

This provisional feature release adds generation-bound discovery and selection
of TVHeadend stream profiles for live playback. Live targets can request a
server timeshift period, and the Media3 coordinator now exposes app-safe
timeshift state plus signed seek, bounded return-live, and server pause and
resume controls without taking ownership of ordinary player controls.

Live playback also reports the current target's canonical TVHeadend
subscription issue through a safe enum. Unknown or localized values map to
`UNKNOWN`; raw server text and stale target state are not exposed.

On Android, `TvheadendServerProfileStore` atomically persists one normalized
endpoint and anonymous or password authentication. Password fields are
encrypted with endpoint-bound associated data and are returned only through an
opaque connectable profile. The deprecated credential store remains binary
compatible over the same record.

This remains a major-zero release. Source, binary, and behavioral compatibility
are provisional, and local or CI verification does not establish Maven Central
availability.

## [0.1.2]

Initial provisional release of the independently maintained TVHeadend SDK. It
provides five focused libraries for HTSP-backed session and metadata workflows,
subscription and timeshift state machines, Android integration, Media3
playback, and reusable test fixtures.

The release includes generation-scoped lifecycle and cancellation, typed EPG
and DVR operations, authenticated artwork, bounded recording-progress policy,
and direct live and recording playback coordination. Growing pass-through
MPEG-TS playback is intentionally limited to stable recording identity and
validated MPEG-2, H.264, or HEVC seek paths; other growing containers and
unvalidated codecs fail closed.

The Media3 module includes the corresponding FFmpeg 6.0 source archive for its
optional GPL decoder fallback. The source, binary, and redistribution evidence
is recorded in `docs/ffmpeg-contingency.md`.

This recovery aligns the tracked Maven OpenPGP public key and approved
fingerprint with the protected release environment. It does not change SDK
runtime behavior or API.

The signed `v0.1.0` and `v0.1.1` tags are retained as historical evidence after
their workflows stopped before signed-bundle retention or publication. No
`0.1.0` or `0.1.1` Maven artifacts or GitHub releases were created.

This is a major-zero baseline. It does not promise source, binary, or behavioral
compatibility or support. Publication and availability are independently
verified external state and are not established by this changelog entry.
