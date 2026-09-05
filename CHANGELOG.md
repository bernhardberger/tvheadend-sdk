# Changelog

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
