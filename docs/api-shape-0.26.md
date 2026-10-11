# 0.26 API shape: design note

**Status:** accepted for implementation, after an Opus and Astra
review pair. Nothing in this note is implemented.

0.26 is the breaking release that reshapes public API so that later releases,
including the 1.x line, can evolve additively. See the [roadmap](roadmap.md)
for scope and order. This note settles the three decisions the rest of 0.26
depends on:

1. the shared failure model;
2. which result and enum types are open;
3. the experimental tier.

It also scopes the remaining 0.26 cleanup.

## Compatibility baseline

The 1.x promise covers Kotlin source compatibility and behavioural
compatibility, not binary compatibility (roadmap decision 1). For public
data classes, it covers these call forms:

- constructor calls, positional or named;
- direct `copy(...)` calls;
- destructuring of existing positions.

Under that promise:

- **Appending a defaulted property is compatible.** It keeps every covered
  call form working. Public data classes therefore do not need to become
  builder-style classes. The 1.x rule is that new properties are appended,
  with a default. The appended property also takes part in `equals` and
  `hashCode`, which is a documented behavioural change in the release that
  adds it.
- **Some uses are not covered.** Callable references to constructors or
  `copy` (`::ChannelService`, `instance::copy`) change arity when a property
  is appended. Java callers of data-class constructors lose their signature
  unless the constructor has `@JvmOverloads`. The compatibility policy
  written in 0.28 must state both exclusions.
- **Adding a value to an enum or a case to a sealed type is a source break**
  for exhaustive `when` expressions. This is the main problem 0.26 solves.
- **API under an opt-in tier is outside the promise.** That covers
  `@SubscriptionInfrastructureApi`, the testing tiers, and the new
  experimental tier.

## 1. Shared failure model

### Problem

In the pre-0.26 API, the same server and transport failures are re-declared many times:

- as payload-free cases in five sealed results: `DvrMutationResult`,
  `DvrProgressResult`, `DvrCutpointsResult`, `StreamProfilesResult` and
  `EpgSearchResult`;
- as four enums: `SessionOperationFailure`, `SubscriptionOperationFailure`,
  `RecordingFileFailure` and `ArtworkFailure`.

All of them originate in the gateway's internal `GatewayResult`, which stays
internal.

The copies disagree:

- `EpgSearchResult` has no `NotReady`, adds `ConnectionChanged`, and calls
  server rejection `InvalidQuery`.
- `StreamProfilesResult` folds a connection change into
  `TransportUnavailable`.
- `ArtworkFailure` and `RecordingFileFailure` both lack `SERVER_REJECTED` and
  `TRANSPORT_UNAVAILABLE`, and add `CONNECTION_CHANGED` and
  `FILE_UNAVAILABLE`.

Every new failure kind breaks every exhaustive consumer.

### Proposal

One open failure type, `TvheadendFailure`, in `sdk-playback`. That is the
base module: every other module depends on it, directly or transitively,
through `api`.

It follows the existing open singleton-set house style (`PlaybackTargetResult`,
`SubscriptionIssue`):

- a private constructor;
- `@JvmField` companion values;
- `toString()` returns only the label;
- stable predicates;
- KDoc requiring a fallback branch.

Initial values:

| Value | Meaning | Predicates |
|---|---|---|
| `NOT_READY` | The session is not `Ready` for this operation | transient |
| `OBSERVATION_EXPIRED` | The captured observation's generation is no longer current | transient, needs fresh observation |
| `CONNECTION_CHANGED` | The connection generation changed during the operation | transient, needs fresh observation |
| `SERVER_REJECTED` | The server refused the request | — |
| `MALFORMED_RESPONSE` | The reply could not be interpreted | — |
| `ACCESS_DENIED` | The account lacks the required right | access related |
| `CONNECTION_LIMIT` | The server's connection or user limit was reached | transient |
| `TIMEOUT` | No reply in time | transient |
| `TRANSPORT_UNAVAILABLE` | The connection failed or closed | transient |
| `NOT_SUPPORTED` | The server, protocol version, or the transport's current state cannot support the operation | unsupported |
| `FILE_UNAVAILABLE` | The recording or artwork file is gone, unreadable, or served inconsistently | — |

The predicates are `isTransient`, `requiresFreshObservation`,
`isAccessRelated` and `isUnsupported`:

- **Transient** means a later attempt may succeed without a configuration
  change. It is not permission to retry automatically.
- **The predicates match the existing media3 sets** that use the same names
  (`TimeshiftCommandResult.CONNECTION_LIMIT` and `.TRANSPORT_UNAVAILABLE` are
  transient only). The 0.28 unification therefore does not change what they
  return.

**Whether the server may have applied a change depends on the operation, not
the failure.** So it is not a failure predicate:

- The `Failed` case of `DvrMutationResult` and `DvrProgressResult` exposes
  `mayHaveApplied: Boolean`.
- The operation sets it from what it knows about the command's stage, not
  from the failure value. It is `false` only when the command provably did
  not take effect. That is the case when it was never sent (`NOT_READY`, or
  `OBSERVATION_EXPIRED` before sending) or when the server explicitly refused
  it (`SERVER_REJECTED`, `ACCESS_DENIED`, `CONNECTION_LIMIT`,
  `NOT_SUPPORTED`).
- Every failure after the command may have reached the server reports
  `true`. That includes `MALFORMED_RESPONSE`, `TIMEOUT`,
  `TRANSPORT_UNAVAILABLE` and `CONNECTION_CHANGED`. It also includes failures
  after server acceptance, such as `DvrMutationCoordinator` returning
  `TransportUnavailable` when confirmation registration fails or the
  generation retires while awaiting confirmation.
- A focused test covers acceptance followed by generation retirement and
  asserts `mayHaveApplied == true`.
- Query results keep their current guidance on their `Failed` KDoc: a
  malformed reply implies neither transport loss nor automatic retry.

**Operation-specific meaning moves to each result's `Failed` KDoc.** For
example, TVHeadend answers an inaccessible channel or tag filter in an EPG
search with its generic rejection, so `EpgSearchResult.Failed` documents
`SERVER_REJECTED` for that case.

### Result shape

Each operation keeps its own sealed result. Success cases stay specific and
closed, and every failure becomes one case, `Failed(failure:
TvheadendFailure)`. An exhaustive `when` over the sealed result therefore
stays valid when new failure kinds are added. Every `Failed` case is a data
class with value equality.

| Result | Success cases | Other cases | Replaced cases |
|---|---|---|---|
| `DvrMutationResult<T>` | `Confirmed`, `AcceptedButUnconfirmed` | `Failed` (+ `mayHaveApplied`) | 9 failure cases |
| `DvrProgressResult` | `Accepted` | `Failed` (+ `mayHaveApplied`) | 9 |
| `DvrCutpointsResult` | `Available` | `Failed` | 9 |
| `StreamProfilesResult` | `Available` | `Failed` | 9 |
| `EpgSearchResult` | `Available` | `Failed` | 9 (`InvalidQuery` becomes `SERVER_REJECTED`) |
| `ArtworkLoadResult` | `Available` | `Unavailable(failure: TvheadendFailure)` | `ArtworkFailure` enum |
| `RecordingFileResult<T>` (infrastructure) | `Ok` | `Failed(failure: TvheadendFailure)`, now a data class | `RecordingFileFailure` enum |
| `SubscriptionOperationResult<T>` (infrastructure) | `Ok` | `Failed` | 7 cases and `SubscriptionOperationFailure` |

The same migration also covers the payloads that carry the old types:

- `TvheadendArtworkLoadException.failure` in `sdk-android`;
- `SubscriptionTerminalReason.OperationFailed` and `SubscriptionSeekResult.Refused`;
- `SessionFailure`'s operation failure (see section 2).

Kept as they are:

- **`PlaybackBindingResult`.** It is local and has no I/O; its cases are
  domain outcomes, not failures.
- **`EpgCoverageAcquisitionResult`.** `Ineligible` and the two `Covered`
  cases are domain outcomes.
- **`SessionCommandResult`.** It reports lifecycle command outcomes.
- **`ServerProfileReadResult`.** It covers local storage, not the server.

The pre-0.26 `SessionOperationFailure` carries a `recoveryDisposition`. That
mapping moves into `sdk-core`, because a playback-module type cannot
reference the core enum:

- It keeps every current mapping, including `MALFORMED_RESPONSE` to
  `EXPLICIT_RETRY`.
- It gives the values the old enum lacked (`NOT_READY`,
  `OBSERVATION_EXPIRED`, `CONNECTION_CHANGED`, `FILE_UNAVAILABLE`) and any
  future value an explicit fallback.

The media3 `TimeshiftCommandResult` and `PlaybackTargetResult` values with
the same failure names are already open sets. Unifying them is deferred to
the 0.28 Media3 boundary work.

## 2. Open and closed types

### Rule

A type is **closed** when its values are defined by the SDK's own model and
adding one would change the meaning of existing values. Typical examples are
lifecycle states, inputs, and success shapes. Closed types stay Kotlin enums
or sealed types, and exhaustive `when` is supported.

A type is **open** when new values follow from the server, protocol, codecs,
or new SDK failure handling. Open types become house-style open singleton
sets, so an exhaustive `when` is impossible by construction rather than by
documentation.

A data-carrying open sealed type becomes a class:

- an open `kind` or `outcome` value;
- its data as properties;
- explicit value equality, so `StateFlow` conflation keeps working.

### Classification

**Open (converted in 0.26):**

| Type | Why open | New shape |
|---|---|---|
| `SessionFailure` | Gained 2 cases in 0.24 | Class with open `kind: SessionFailureKind`, `operationFailure: TvheadendFailure?` and `recoveryDisposition` derived from both. Value equality, because `SessionState.Unavailable` sits in a `StateFlow` |
| `SessionRecoveryDisposition` | Its KDoc already requests a fallback, but it is a plain enum | Open set |
| `DvrEntryState` | Server-defined; has `UNKNOWN` | Open set |
| `DvrSubscriptionError` | Server error codes grow | Open set (align with `SubscriptionIssue`) |
| `DvrCutpointAction` | Wire-defined; has `UNKNOWN` | Open set |
| `DvrChangeKind` | Grows with DVR lifecycle detail | Open set |
| `SubscriptionStreamType` | New codecs (VP9, AV1, Opus) | Open set |
| `PlaybackStopResult` | Grew in 0.18 and 0.24 | Class: open `outcome` plus `finalSubscriptionIssue`, value equality |
| `PlaybackShutdownResult` | Drain outcomes still moving | Open set |
| `PlaybackRecoveryReason` | Grew in 0.24 | Open set |
| `TvheadendDiscoveryFailure` | Platform failures grow | Open set |

**Already open, unchanged:** `SubscriptionIssue`, `PlaybackTargetResult`,
`TimeshiftCommandResult`, and the new `TvheadendFailure`.

**Closed, unchanged:**

- **Session and capabilities:** `ServerAuthentication`, `SessionState`,
  `SessionCommandResult`, `CapabilityAccess`, `RecordingProgressCapability`.
- **Playback binding:** `PlaybackBinding`, `PlaybackBindingResult`,
  `RecordingPlaybackAdmission` (after the rename below).
- **DVR inputs and changes:** `DvrPlaybackExit`, `DvrResumeOffer`,
  `DvrSchedule`, `RecordingRuleChannel`, `DvrMutationKind`, `DvrChangeOrigin`.
- **Repository states:** `ChannelRepositoryState`, `EpgRepositoryState`,
  `DvrRepositoryState`, `DvrConfigurationsState`, `DvrDiskSpaceState`,
  `RetainedMetadataAuthority`.
- **EPG coverage:** `EpgCoverageAcquisitionResult`,
  `EpgCoverageBatchSettlement`.
- **Profiles, discovery and connectivity:**
  `ServerProfileAuthenticationMode`, `ServerProfileReadResult`,
  `ServerProfileEditReadResult`, `TvheadendDiscoveryState`,
  `TvheadendConnectivityStatus`.
- **Genres:** `ContentCategory` and `ContentSubgenre`. These are fixed by
  ETSI EN 300 468; unknown codes already map to no genre.
- **Playback control and media3 state:** `RecordingPlaybackStart`,
  `LiveSubscriptionPriority`, `SubscriptionIssueCategory`,
  `PlaybackTargetDisposition`, `TimeshiftCommandDisposition`,
  `PlaybackOutcomeCategory`, `TimeshiftSeekSelection.Boundary`,
  `LiveTimeshiftState`, `LivePlaybackObservation`,
  `TimeshiftWallClockMapping`, `TimeshiftPlaybackPosition`,
  `TimeshiftContentSeekResult`, `LivePlaybackTargetResult`.

Infrastructure-tier types (`SubscriptionEvent`, `SubscriptionTermination`,
`SubscriptionTerminalReason`, `SubscriptionSeekResult`, and others) are
outside the promise and keep their current shape. Only their failure
payloads move to `TvheadendFailure`.

## 3. Experimental tier

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This TVHeadend SDK API is experimental and may change in any minor release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalTvheadendApi
```

- It lives in `sdk-playback`, next to `SubscriptionInfrastructureApi`, so
  every module can use it.
- The level is `ERROR`, consistent with the three existing tiers. The player
  opts in per file, which keeps experimental use visible in review.
- An experimental API becomes stable in a minor release by removing the
  annotation. Whether to graduate is decided per release, based on use in the
  player.

Decided (maintainer, 2026-10-11):

- **Growing-recording playback: experimental.** Only the growing-specific
  surface gets the annotation: the growing admission case, the growing
  reader and lease, and the resume-outcome observation once it is added.
  Growing resume already works today. What is still open is how its outcome
  is observed, and the scope for non-TS containers. Completed-recording
  playback and the shared admission type stay stable.
- **Rule-to-upcoming-matches helper (0.27): experimental.** It is new and
  unproven.
- **`DvrRepository.changes`: stable.** Its contract is more than a flow:
  loss, buffering, reconnect suppression, and generation checks. That whole
  contract stays compatible. Before 1.0 the player must exercise those
  semantics; otherwise it becomes experimental at 1.0 under the readiness
  criteria.

Not candidates:

- **Media3-facing API** (`TvheadendAudioOutputProvider`,
  `createTvheadendRenderersFactory`). The 0.28 Media3 boundary work handles
  these.
- **Testing APIs.** These keep their own tiers.

## 4. Remaining 0.26 scope

### Typed DVR fields

The rule and entry types expose every server option, typed. The player's
series-recording brief chooses which options to show; it does not change
the SDK types. The brief therefore does not block 0.26.

Update requests keep every field nullable:

- `null` means "leave unchanged", as today.
- An explicit value, including a server-default value where TVHeadend has
  one, means "set".
- Title-only updates must keep preserving all other settings, with
  regression tests.

| Field | Today | 0.26 |
|---|---|---|
| `daysOfWeekMask` | `Long?` | `Set<java.time.DayOfWeek>?` |
| Timerec start and stop (`*MinutesSinceMidnight`, 0–1439) | `Int?` | `java.time.LocalTime?` |
| Autorec window bounds (`*MinutesSinceMidnight`, -1 or 0–1439) | `Int?` | `AutorecTime?`, a closed type with `AnyTime` (TVHeadend's -1) and `At(time: LocalTime)` |
| `startExtraMinutes`, `stopExtraMinutes` | `Long?` | `Duration?` |
| `priority` | `Long?` | `DvrPriority?` (open set of TVHeadend priorities, including its default value) |
| `duplicateDetection` | `Long?` | `DuplicateDetection?` (open set, TVHeadend modes) |
| `broadcastType` | `Long?` | `BroadcastType?` (open set) |
| `DvrEntry.duplicate` | `Long?` | `Boolean?`. HTSP sends a 0/1 flag (`htsp_server.c:1188`), which the HTSP library already exposes as `Boolean?` |
| `DvrEntry.image`, `fanartImage`, `EpgEvent.image` | `String?` | `ArtworkId?` |
| `contentType` next to `contentGenre` | duplicated | `contentType` removed |

Decided (maintainer, 2026-10-11): use `java.time`, not `kotlinx-datetime`.

- **Why not `kotlinx-datetime`.** It is still a 0.x library and has made
  breaking changes; 0.7 moved `Instant` into the standard library. Exposing
  its types would tie SDK 1.0 public API to its evolution.
- **Why `java.time`.** It is stable and adds no dependency. Android has had
  it natively since API 26, so minSdk 28 needs no desugaring, and a JVM Linux
  application has it too.
- **The cost.** A later Kotlin Multiplatform migration becomes more
  expensive. That trade-off is accepted. Roadmap decision 11 is relaxed
  accordingly.

Time values must use whole minutes. TVHeadend encodes autorec "any time"
as -1. It sends -1 (`htsp_server.c` `htsp_build_autorecentry`), and when
it stores a time it turns blank values and values of 1440 or more into -1
(`dvr_autorec.c` `dvr_autorec_entry_class_time_set`). An earlier draft of
this note named 1440 as the sentinel; 1440 is only a value the server
clamps. `AutorecTime.AnyTime` reads every negative value and every value of
1440 or more, and is written as -1. Mapping it to midnight would change its
meaning, and mapping it to `null` would collide with "leave unchanged".

Timerec times have no "any time" value. TVHeadend uses -1 for a time that
is not set, and such a rule never fires. A timerec time outside 0–1439
reads as `null` on that field; the rule itself is kept.

### Removals and renames

- Remove `TvheadendCredentialStore`, `CredentialReadResult`,
  `CredentialOperationResult`, the legacy v1 storage path, and the Konsist
  test that pins its deprecation.
- Remove `PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED`.
- Rename `RecordingPlaybackAdmission.GrowingStartOverOnly` to `Growing`.
- Make `DvrEntry.path`, `DvrRecordingFile.path`, `owner` and `creator`
  non-public.
- Remove `EpgCoverageBatchSettlement.channelIdValue`, which duplicates
  `channelId`.
- Move `TimeshiftTestFixture` out of `sdk-media3` main, or put it behind
  `@TvheadendTestingApi`. `SessionGenerationTestAuthority` and
  `TvheadendTestResultFactory` already carry `@TvheadendTestingApi` and need
  no change.
- `createTvheadendSession` no longer ignores conflicting policies.
  Decided (maintainer, 2026-10-11):
  - **Conflicting policies throw.** When a process-wide owner exists, a call
    with policies whose configuration differs throws
    `IllegalStateException`. A call with equal configuration returns the
    existing owner.
  - **Calls without policies still look up the existing owner**, whatever
    its configuration, as `TvheadendSessionFactoryTest` requires today.
  - **Comparison is by configuration value, not object identity.**
    `EpgCoveragePolicy` and `MetadataCachePolicy` currently use identity
    equality, so they gain value equality. The cache root is compared under a
    documented path-equivalence rule.
  - **Comparison and acquisition are atomic.**
  - **The exception message contains no path.**
  - **Tests** cover equal fresh policies, a mismatch, concurrent
    acquisition, and replacement after shutdown.
- DVR mutation and progress failures caused by a retired connection
  generation report `CONNECTION_CHANGED`, not `TRANSPORT_UNAVAILABLE`, so
  callers take a fresh observation before retrying an operation that may
  have applied. `mayHaveApplied` is unaffected. When the cause is a
  session shutdown or disconnect rather than a generation change:
  - a command that was never sent reports `NOT_READY`, unless the caller's
    observation was already stale, in which case it reports
    `OBSERVATION_EXPIRED`;
  - a command that may already have been sent reports `CONNECTION_CHANGED`,
    because the caller needs a fresh observation before retrying it.

### Not in 0.26

- **Converting public data classes to builders.** That is not needed under
  the source-compatibility promise; see the baseline above.
- **Media3 types and opt-ins.** Deferred to 0.28.
- **Replacing `java.io.File`, `InputStream` and `java.text.Collator`** in
  public `sdk-core` signatures. That was only needed for strict
  multiplatform neutrality, which is no longer a goal.

## Invariants the migration keeps

- Mapping code never catches `CancellationException`; it always propagates.
- `toString()` of `TvheadendFailure`, every new open set, and every `Failed`
  case renders only labels. No endpoint, credential, path, username, raw
  server text, or subscription id appears.
- `GatewayResult` stays `internal`, and no HTSP type appears in a public
  signature.
- Stale-generation behaviour is unchanged. The existing regression tests
  move with the types.

## Order and verification

1. Add `TvheadendFailure`, `ExperimentalTvheadendApi`, and the open-set
   conversions, with focused tests for predicates, equality, and mappings.
2. Migrate the five sealed results, the four failure enums, and their
   carriers. Migrate the `sdk-testing` fakes and `consumer-contract` in the
   same step, because consumer-contract is a CI gate.
3. Type the DVR fields, including mapping tests against the HTSP values and
   omission-preservation tests.
4. Removals and renames.
5. Update ABI dumps through Gradle; mark every break `BREAKING` in the
   changelog.
6. Migrate the player in lockstep.

An Opus and Astra review pair reviews the work after steps 1–2 and after
step 4.

## Maintainer decisions

All three open questions were decided on 2026-10-11, after a second opinion
from Astra. The decisions are recorded in their sections:

- `java.time` rather than `kotlinx-datetime`;
- the experimental scope;
- `createTvheadendSession` throwing on conflicting policies.
