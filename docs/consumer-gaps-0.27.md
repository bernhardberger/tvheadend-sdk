# 0.27 consumer gaps: design note

**Status:** draft for the review pair and owner. The signatures below are
proposals, not implemented API. Items 4 and 8 wait for owner decisions.

0.27 removes consumer workarounds before 1.0 freezes their replacement. It
keeps the [0.26 compatibility baseline](api-shape-0.26.md): Kotlin source and
behavioural compatibility, defaulted appended properties, open server-defined
values, and closed SDK-defined inputs and success shapes.

**D-A (settled):** roadmap decision 2 (`docs/roadmap.md:42-45`) governs:
every new public surface in items 1–8 is `@ExperimentalTvheadendApi`, including
properties, overloads, result types and consumer-fake additions. They graduate
in a later minor; none is stable on arrival. Item 9 is documentation only.
**D-B (settled):** the two questions below remain open, with these recommendations.

| Item | Owning module | New API | Tier | Open owner decisions | Proposed slice order |
|---|---|---|---|---|---|
| 1. Recover a recording at a position | `sdk-media3` | `recoverRecordingTarget`, pending-position observation | Experimental | No | 5a–5c |
| 2. Bounded live retune budget | `sdk-playback` | `LiveRetuneBudget`, `LiveRetuneDecision` | Experimental | No | 3 |
| 3. Live-start buffer and timeshift skips | `sdk-media3` | SDK-owned load-control handle and mutable live-start buffer | Experimental | No | 6a–6c |
| 4. Watched/unwatched and reset resume | `sdk-core` | `DvrRepository.updateWatchState`, `resetResume` (**BREAKING** for implementors) | Experimental | Yes | 7, decided |
| 5. Metadata refresh times | `sdk-core` | Observation-side domain times and per-channel query times | Experimental | No | 4 |
| 6. Recording stream information | `sdk-core` | `DvrRecordingFile.streams` | Experimental | No | 2 |
| 7. Radio/TV classification | `sdk-core` | `Channel.mediaKind`, `ChannelService.mediaKind` | Experimental | No | 1 |
| 8. Series rule upcoming recordings | `sdk-core` | `SessionObservation.upcomingRecordings` | Experimental | Yes | 8, after decision |
| 9. Coordinator multiplicity | `sdk-media3` documentation | Supported-topology documentation; no new declaration | Documentation | No | 0 |

Slice sizes below count production, tests, fakes, consumer-contract and API/docs
files in the SDK, approximately; Player changes are additional. R8 verification
remains a separate 0.27 roadmap item, outside this API note.

## Owner decisions

1. **Should marking watched or unwatched also clear resume by default?**
   **Decided: yes, both actions clear resume.** Follow common player practice
   (Plex, Jellyfin/Emby, Kodi): one Boolean watch-state command sets or resets
   the count and sends position zero atomically at the HTSP-command boundary.
    No patch type. **Follow-up decision (2026-10-12):** include standalone
    `resetResume` as a comfort feature, avoiding the watched → unwatched workaround.
    It clears resume and preserves play count. See item 4.
2. **Does “upcoming matches” mean recordings the server has scheduled, or all
   EPG events a rule might match?** Recommend **server-scheduled recordings
   only** for 0.27, named `upcomingRecordings`. The existing rule links are
   authoritative; local matching cannot promise the server's duplicate,
   retention, access, scheduling and rule semantics. “Known start” means
   scheduled `DvrEntry.start` (wire `start`), without subtracting `startExtra`.
   Recommend excluding in-progress entries (`RECORDING` and `RECORDING_ERROR`),
   even when their scheduled start lies in the interval; this is an upcoming
   list, not an “on now” list. See item 8.

## Contract rules

- Every public suspending server round trip has a typed outcome.
  `CancellationException` always propagates, including after dispatch;
  cancellation does not establish that a mutation was unapplied.
- New server failures use `TvheadendFailure`. Mutation failures carry
  `mayHaveApplied`: false only when provably not sent or explicitly refused;
  true for an uncertain reply or generation retirement after possible dispatch.
  A successful reply is not necessarily asynchronous metadata confirmation.
- Existing `PlaybackTargetResult` stays the open playback outcome set; its
  unification with the shared failure model belongs to 0.28. Local queries and
  budgeting do not invent server-failure cases.
- New open singleton sets use private constructors, `@JvmField` companion
  values, label-only `toString()`, and KDoc requiring a fallback. Closed types
  explicitly support exhaustive `when`. Observation payloads have value
  equality, immutable collection snapshots and defaulted appended fields.
- All proposed types and results obey the existing redaction boundary:
  no credentials, tickets, paths, hosts, endpoints, usernames, raw server
  errors or subscription ids in diagnostics, errors, logs or `toString()`.
  Payloads with server text use `<redacted>` rather than generated rendering.
  Validation messages name the constraint, never the supplied value.
- Signatures use the existing `kotlin.time.Duration` and `kotlin.time.Instant`
  playback/observation types. This does not reopen 0.26's choice of `java.time`
  for weekdays and local rule times.
- Tests above the gateway constructor-inject `ProtocolGateway` fakes. Only
  gateway mapping tests fake `HtspConnection`. Media3 tests inject the existing
  coordinator-player/resume-player interfaces and clocks. No new framework,
  parser or codec is needed. Consumer fakes stay JVM-only in `sdk-testing`.
- Experimental additions to existing types are annotated individually; retain
  existing factories and add experimental overloads with a required new argument
  where needed, rather than forcing existing callers to opt in. Experimental
  scope does not excuse a breaking change to an existing implementor contract.

## 1. Recover a recording at a position

### Current state

The coordinator accepts only `START_OVER` or the server's saved `RESUME`:
`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:628-651`.
The growing rule is already owned by the SDK: 3 s behind the verified extent,
20 s to become seekable, cancellation on viewer seek/replacement/stop, and a
progress hold while the seek is pending. Completed resumes keep waiting for a
seekable timeline, with only their progress hold bounded to 60 s
(`docs/growing-recording-evaluation.md:286-324`;
`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendRecordingResume.kt:156-213`).

The Player copies the rule, constants and pending-seek bookkeeping in
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt:2195-2294`.
It also suppresses recovery near orderly completion, using the old duration
when known and the new completed timeline otherwise.

### Proposal

Add a member to `TvheadendPlaybackCoordinator` in `sdk-media3`:

```kotlin
@ExperimentalTvheadendApi
public suspend fun recoverRecordingTarget(
    binding: PlaybackBinding.Recording,
    position: Duration,
    previousDuration: Duration? = null,
    fallbackStart: RecordingPlaybackStart = RecordingPlaybackStart.RESUME,
): PlaybackTargetResult

@ExperimentalTvheadendApi
public val pendingRecordingRecoveryPosition: StateFlow<Duration?>
```

This replaces the target and supplies a one-shot recovery position; it is not
a seek on whichever item happens to be current. The caller binds the intended
recording in a fresh observation and passes measurements for that same
recording. The Player still owns route identity and capture of its lost target.

- Require finite non-negative whole-millisecond inputs; a supplied previous
  duration must be positive. Zero position uses `fallbackStart`.
- For a newly admitted completed recording with known `previousDuration`,
  `position >= previousDuration * progressPolicy.orderlyCompletionFraction`
  rejects recovery **before installation** and uses `fallbackStart`, including
  the supported server bookmark when that is `RESUME`.
- With unknown `previousDuration`, install `START_OVER` and wait for the new
  completed timeline. At or beyond its orderly-completion cutoff, **drop only
  the pending seek and continue from the installed zero start**. Do not apply
  `fallbackStart` or schedule a second bookmark seek at that point. Below the
  cutoff, seek when seekable. With either duration case, a pending position at
  or beyond the installed media end is dropped, also without late fallback.
  This preserves the two distinct Player paths in
  `/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt:2223-2237,2273-2287`
  and its `START_OVER` installation / late-cutoff flag at
  `/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt:975-988,1035-1043`.
- Otherwise completed recovery uses the existing completed resume state
  machine. Growing recovery uses the same 3 s/20 s rule as server resume, with
  no orderly-completion cutoff against a provisional extent. A target admitted
  as growing keeps that rule if the recording completes meanwhile.
- Keep growing admission gates and the admission re-read on completion.
  An explicit recovery position survives that re-read; the re-read server
  bookmark is used only when falling back to `RESUME`.
- For **completed** recordings the explicit position applies even when
  `RecordingProgressCapability` is UNKNOWN or UNSUPPORTED: it is a caller's
  local measurement, not a server bookmark. Progress reporting stays disabled
  in those cases. Fallback `RESUME` keeps today's support gate. For **growing**
  recordings keep today's admission results: UNKNOWN is `NOT_READY`,
  UNSUPPORTED is `PROGRESS_UNSUPPORTED`; an explicit position does not bypass
  admission. These are separate from the saved-position gate at
  `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:922-955`.
- Carry the chosen recovery position into the coordinator's progress hold and
  closing-report floor. Repeated recovery before the seek settles must carry
  the owed position, not the temporary zero position. Viewer seek, departure,
  replacement and teardown cancel it; an old callback cannot seek a new item.
- The experimental flow exposes that owed explicit-recovery position while
  pending, and null after seek issuance, cancellation, abandonment or target
  retirement. Publish it with target installation/retirement, fenced against
  old callbacks. It is not a landing measurement or a history of resume outcomes.
  The Player reads its current value when capturing its installed recording
  under its existing target-command fence, falling back to measured position.
- Return the existing installation result. `STARTED` means installation, not
  proof of a later seek landing. The narrow pending-position observation avoids
  copying timeout/settlement logic. Both additions use the experimental tier.

**Open/closed and redaction:** existing open `PlaybackTargetResult`, closed
`RecordingPlaybackStart`; no new result taxonomy or diagnostic payload.
The method and observation both require the experimental opt-in (D-A).

**Tests and fakes:** extend coordinator, resume-state-machine and recording
admission tests with injected player/time fakes: exact completed position,
known/unknown duration cutoff including exact-boundary and both fallback choices,
all three progress capabilities for completed/growing admission, growing edge clamp, short extent, timeout,
completion during admission, repeated retry, cancellation and progress-floor
preservation on early stop. Verify the pending-position flow clears on every
settlement and cannot be cleared by an old target. Keep existing extractor
landing evidence; this change does not replace it. `sdk-testing` gains recovery-position recording
fixtures only if consumer tests need them; it gains no Android coordinator fake.

**Player migration (full rule replacement, not full recovery ownership):** delete `RecordingRecoverySeek`,
`recordingRecoverySeek`, `recordingRecoverySeekMs`, both growing constants,
and runtime pending-seek bookkeeping/application. Call `recoverRecordingTarget` after
binding on Retry/route restoration. Keep the app's lost-target identity and
position capture; read the SDK's pending-position flow instead of maintaining
`PendingRecordingRecovery` to recover again before a seek settles.
The retained capture/fence is at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt:857-879`;
the deleted application of the copied rule is at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt:893-920`.
Retry/route choice, identity matching and play intent remain in the Player.

**Sub-slices and stop points:**

| Slice | Scope | Files | Stop point |
|---|---|---|---|
| 5a | Internal explicit-position admission and completed/growing resume reuse; cutoff/capability tests | 5–7 SDK | Tests prove both cutoff paths and all capability gates; no new public entry yet |
| 5b | Experimental method/flow, command and target fencing, progress floor, consumer contract and documentation | 7–10 SDK (overlaps 5a) | Repeated-retry, cancellation, replacement and progress tests pass; API review accepts both declarations |
| 5c | Player migration and removal of duplicated rule/pending seek | 3–5 Player | Existing recovery/route/interruption tests use SDK recovery; no copied constants remain |

Approximately **11–16 distinct SDK files plus 3–5 Player files** overall.
Stop after each verified slice; do not call item 1 complete before 5c.
**Owner decision:** no; preserve the existing Player rule and move its execution
beside the SDK's resume state machine.

## 2. Optional bounded live retune budget

### Current state

The coordinator delivers `onRecoveryRequired` but owns no autoplay or app
retry scheduling
(`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:420-450,895-918`).
The Player's budget is five attempts delayed by 0, 2, 5, 10 and 20 s, reset
after 60 s since the previous **admission**, not 60 s of healthy playback:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/LiveRecoveryBackoff.kt:12-64`.
Its controller admits under the target lock, then delays outside it:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/LiveRecoveryController.kt:35-73`.

### Proposal

Use an opt-in-by-construction local helper in `sdk-playback`, rather than make
the coordinator retune behind an application's pause/focus policy:

```kotlin
@ExperimentalTvheadendApi
public class LiveRetuneBudget(
    delays: List<Duration> = listOf(0.seconds, 2.seconds, 5.seconds, 10.seconds, 20.seconds),
    resetAfter: Duration = 60.seconds,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    public fun nextAttempt(): LiveRetuneDecision
    public fun reset(): Unit
}

@ExperimentalTvheadendApi
public sealed interface LiveRetuneDecision {
    public data class Admitted(
        public val attempt: Int,
        public val delay: Duration,
    ) : LiveRetuneDecision
    public data object Exhausted : LiveRetuneDecision
}
```

One budget belongs to one app-managed logical live target, surviving retunes
of that target. Explicit channel change, viewer Retry and teardown reset it.
Pausing or deferring an unadmitted request spends nothing; cancelling after
admission does not refund it. The helper neither sleeps nor launches work.
Exhaustion does not stop the Player: the app applies its existing failure UI
and stop policy. Omitting the helper preserves current SDK behaviour.

Use the supplied monotonic time source, atomically claim attempts, and
defensively copy delays. `Admitted.attempt` is **1-based**, restarting at 1
after refill/reset, as in
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/LiveRecoveryBackoff.kt:48-57`.
An empty delay list admits nothing. Require finite non-negative delays and a
finite positive reset interval. The 60 s boundary is inclusive. These choices
preserve the existing algorithm rather than introduce a new recovery policy.

**Open/closed and redaction:** `LiveRetuneDecision` is closed (local admission
or exhaustion); no server round trip, failure model or `mayHaveApplied`.
Render only attempt counts and durations; no target identifiers are retained.

**Tests and fakes:** pass stdlib `TestTimeSource` to the public constructor;
prove the exact schedule, exhaustion, reset boundary, defensive copy, explicit
reset and atomic claims, including that exhausted calls do not move the previous
admission mark. Player tests advance the same source without sleeping. No
testing-tier factory or `sdk-testing` addition is needed; test the real policy.

**Player migration:** delete `LiveRecoveryBackoff`, `LiveRecoveryAttempt` and
their default constants. `LiveRecoveryController` calls this helper and keeps
its fence, focus/background checks, cancellable delay and target installation.
This **fully replaces the backoff**, not the controller: dispatch/delay and
interrupted/deferred recovery remain at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/LiveRecoveryController.kt:49-84,87-170,172-228`.
**Size:** 4–6 files. **Owner decision:** no; bounded policy is optional and
does not transfer application playback ownership.

## 3. Live-start buffer without another unstable public type

### Current state

`createTvheadendLoadControl()` returns Media3 `LoadControl`, using 2 s startup
and 1.5 s rebuffer thresholds:
`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendLoadControl.kt:8-36`.
The Player wraps it to change live startup dynamically and treat a server skip's
one restart as a startup, including an already-running rebuffer. It explicitly
forwards Media3 Java default methods and uses a 3 s post-result grace:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/StartupBufferLoadControl.kt:18-44,59-156`.
Construction is at
`/root/projects/tvheadend-player/app/src/main/java/at/bernhardberger/tvhplayer/di/AppModule.kt:90-110`.

### Proposal

Bring forward only the load-control handle part of the 0.28 boundary. Keep the
old zero-argument function as the existing unstable entry point for 0.27. Add
an overload whose required SDK-owned argument selects an SDK-owned return:

```kotlin
@ExperimentalTvheadendApi
public class TvheadendLiveStartBuffer(initial: Duration = 2.seconds) {
    public var duration: Duration
}

@ExperimentalTvheadendApi
public class TvheadendLoadControl internal constructor(/* internal delegate */) {
    public fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder
}

@ExperimentalTvheadendApi
public fun createTvheadendLoadControl(
    liveStartBuffer: TvheadendLiveStartBuffer,
): TvheadendLoadControl

// New overload; the existing overload remains available.
@ExperimentalTvheadendApi
public fun createTvheadendPlaybackCoordinator(
    player: ExoPlayer,
    loadControl: TvheadendLoadControl,
    progressPolicy: DvrProgressPolicy = DvrProgressPolicy(),
    recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit = {},
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): TvheadendPlaybackCoordinator
```

Put these declarations in a **new** `TvheadendLoadControlConfiguration.kt`
under the same `sdk-media3` package, with **no `@file:UnstableApi`**. The existing
file has that annotation at
`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendLoadControl.kt:1`.
Use `@OptIn(UnstableApi::class)` only on SDK implementation sites calling the
unstable delegate/setter, not a propagated unstable marker on the new types.

`configure` installs the internal Media3 delegate on an **application-created**
builder and returns that builder. The application still builds and releases
its Player. No new public type implements or exposes `LoadControl`, its
parameters, allocator or callbacks. `ExoPlayer.Builder` is the stable Media3
construction boundary; the unstable setter is called inside the SDK.

The same handle is passed to the coordinator for internal target/skip fencing.
It belongs to one Player/coordinator pair; sharing it across Players is outside
the contract. Buffer duration is thread-safe and read on the next decision;
require a finite non-negative whole-millisecond value fitting Media3's integer
millisecond range. Preserve the SDK's 2 s default; the Player supplies its
current learned/selected value. The wrapper's **initial** value is exactly
500,000 µs (500 ms), not a lasting fixed policy:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/StartupBufferLoadControl.kt:50,152-156`.
Settings/learning can replace it at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/StartupBufferController.kt:67-84`.

The pinned Media3 1.11.1 source confirms the boundary: the `Builder` class is
unannotated at
[`libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java:215-220`](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java#L215-L220),
while its `setLoadControl` method is `@UnstableApi` at
[`libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java:679-693`](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java#L679-L693).
The period context and rebuffer clock exist in
[`libraries/exoplayer/src/main/java/androidx/media3/exoplayer/LoadControl.java:41-94`](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/LoadControl.java#L41-L94).

- Classify the queried period by the SDK live-item marker, not `isDynamic`.
  Completed/growing recordings and unknown periods delegate unchanged.
- Only actual coordinator server skips arm the mark. Cover relative/absolute
  timeshift seeks and return-to-live. A skip's in-flight restart or one begun
  within 3 s after completion uses the live-start threshold once; a rebuffer
  already in progress is eligible. Clear on refusal, cancellation, departure,
  replacement and teardown. An uncertain dispatched skip follows the existing
  typed command disposition, not a blanket “failure means not sent” rule.
- Fence marks by target and period, preventing an old result from affecting a
  replacement. Ordinary rebuffers retain 1.5 s. Playback-speed scaling,
  target-live-offset capping, allocation, loading and backbuffer delegate as
  today. Forward all current non-deprecated `LoadControl` methods explicitly.

**Threading:** coordinator skip-start/completion/cancellation and target-retire
marks are written on the Player's **application looper**, via the existing
player-looper executor (the actor/gateway coroutine marshals there before
publishing). Arm before dispatch; capture whether already buffering on that
looper. `shouldStartPlayback` reads and atomically consumes the mark on Media3's
**playback thread**, which alone owns its scratch period/window objects. Use an
atomic, target/period-fenced mark and CAS consumption; completion cannot resurrect
a consumed or replaced mark. Buffer setters may run on any thread, with volatile
or atomic publication. Use Android monotonic elapsed realtime for skip bounds,
the same clock domain as `lastRebufferRealtimeMs`. The Player copy's existing
publication/consumption is visible at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/StartupBufferLoadControl.kt:41-57,69-80,114-149`.

**0.28 source compatibility:** the new overload, handle, mutable buffer and
coordinator argument remain unchanged. 0.28 can move/retire the old unstable
zero-argument entry point and compose this handle into its broader SDK-owned
configuration without changing these calls. Merely appending a scalar to the
old `LoadControl`-returning function would leave a removal problem in 0.28;
this overload avoids it. This is the intended source-compatible 0.28 path;
the 0.27 surface is still experimental and graduates only in a later minor.

**Open/closed and redaction:** SDK-owned configuration objects, not server
sets. No suspending API or new outcomes; existing timeshift results remain
authoritative. Handles render labels only and expose no period or subscription
identity.

**Tests and fakes:** extend load-control and coordinator-player tests for live
versus dynamic recording, threshold updates, normal rebuffer, each skip entry
point, already-buffering skips, grace expiry, refusal/cancellation, replacement
and overlapping results. Port the Player's forwarding and playback restart
tests to SDK Media3 tests. A consumer-contract call must use the new load-control
types without a Media3 unstable opt-in; the coordinator's existing annotation
is separately removed in 0.28. No Media3 fake goes into `sdk-testing`.

**Player migration (full wrapper replacement):** delete `StartupBufferLoadControl`. Configure the SDK
handle in `AppModule`, pass it to the coordinator, and let
`StartupBufferController` set `TvheadendLiveStartBuffer.duration`. Delete its
load-control skip signalling; retain its learning, settings and presentation
window policy. Its learning window may still observe skip outcomes, but must
not arm the SDK load-control mark itself.
The settings/learning publication remains at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/StartupBufferController.kt:67-84`;
target/skip learning-window management remains at
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/StartupBufferController.kt:98-159`,
after removing calls into the deleted wrapper. Do not delete those methods
wholesale: their `cut`/`armIfPlayIntended`/timer work is application policy.

**Sub-slices and stop points:**

| Slice | Scope | Files | Stop point |
|---|---|---|---|
| 6a | Unannotated-file public handle/buffer overload, forwarding and dynamic threshold | 5–7 SDK | No-Media3-opt-in load-control consumer compiles; forwarding/period/threshold tests pass; Player still uses its wrapper |
| 6b | Coordinator overload, application-looper publication, period-fenced skip integration | 7–10 SDK (overlaps 6a) | All skip paths, two-thread consumption, grace, cancellation and late-result tests pass |
| 6c | AppModule/controller/runtime migration and ported playback regression tests | 5–7 Player | Wrapper and its signalling are removed; learned buffer and skip restart tests pass |

Approximately **11–16 distinct SDK files plus 5–7 Player files** overall.
6a alone does not replace the Player wrapper; stop at its verified boundary
until 6b is ready, and complete the migration only in 6c.
**Owner decision:** no; this is the narrow compatibility bridge required by
the already accepted 0.28 boundary, with existing default behaviour preserved.

## 4. Watched/unwatched and standalone resume reset outside playback

### Current state and wire facts

The existing playback API exposes `playCount` and `playPosition`, but its progress input requires
a position and treats `markWatched` as an **increment**, not an idempotent set:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrProgress.kt:13-40`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:679-692`.
Before this slice, `DvrRepository` had no watch-state command
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt:838-922`).
The Player shows play count and offers resume/start-over, but not a watch-state
mutation:
`/root/projects/tvheadend-player/app/src/main/java/at/bernhardberger/tvhplayer/ui/screens/recordings/RecordingsContent.kt:325-329`;
`/root/projects/tvheadend-player/app/src/main/java/at/bernhardberger/tvhplayer/ui/screens/recordings/RecordingsModals.kt:130-152`.

Verified upstream:

- `updateDvrEntry` accepts both fields independently. `playcount=1` sets a zero
  count to one and preserves an existing positive count; `0` resets it.
  `INT32_MAX-1` keeps it; `INT32_MAX` increments. Values above `INT_MAX` become
  increment, so never use an unsigned maximum as a “set watched” sentinel.
  See `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.h:25-29`
  and `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:2214-2236`.
  Ordinary values 2 through `INT32_MAX-2` set that count directly; omitted/KEEP
  leaves the local update argument at -1. There is no explicit saturating guard
  on `de_playcount + 1` in the INCR branch; this API uses SET/RESET, not INCR.
  The setter assigns the count and arms/disarms watched retention handling:
  `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr_db.c:2419-2434`.
- Omitted position stays unchanged; explicit zero resets it. The server clamps
  larger positions to `INT_MAX`. Progress updates apply only to server states
  `DVR_RECORDING` or `DVR_COMPLETED`, including their error/file-missing variants:
  `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr_db.c:2486-2494`.
- This is **not** a state-based refusal. Only `DVR_SCHEDULED` is editable
  (`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr.h:536-537`).
  Its editable update path does not apply progress; missed/invalid entries take
  the non-editable path but skip the recording/completed progress branch:
  `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr_db.c:2463-2514,2516-2679`.
  `updateDvrEntry` nevertheless returns success after the update call:
  `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:2238-2243`.
  Missing ids and access failures are actual pre-update error replies at
  `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:2148-2160,2192-2194`.
  The wire reports recording/completed error and missing-file conditions on
  those same base states, not additional C scheduling states:
  `/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:1157-1194`.
- HTSP already carries nullable `playCount` and `playPositionSeconds`, requires
  recorder access and protocol 27 for these fields:
  `/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/HtspDvrRequests.kt:169-213`.
  Its codec omits null fields:
  `/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/HtspRequestCodec.kt:106-107`.

### Proposal

In `sdk-core`:

```kotlin
// Member of DvrRepository:
@ExperimentalTvheadendApi
public suspend fun updateWatchState(
    currentSession: CurrentSessionObservation,
    id: DvrEntryId,
    watched: Boolean,
): DvrProgressResult

@ExperimentalTvheadendApi
public suspend fun resetResume(
    currentSession: CurrentSessionObservation,
    id: DvrEntryId,
): DvrProgressResult
```

**Owner decision:** `watched=true` maps to SET (1, preserving an existing
positive count upstream), false to RESET (0). Both send position zero in
**one** `updateDvrEntry` command. There is no `DvrWatchStateUpdate` patch type.
The standalone `resetResume` comfort command sends position zero and omits
play count, preserving it with the same semantics as `DVR_PROGRESS_KEEP_PLAY_COUNT`.
The HTSP library omits null `playCount`; upstream leaves the initial -1 (unchanged)
when `playcount` is absent, just as for KEEP
(`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:2179,2214-2234`).
Both commands share the eligibility, result, latch and generation rules below.
Do not read-modify-write the old count or position and do not fake playback.
Only these fields and the entry id are sent. Do not synthesize other update
fields. Do not promise universal preservation by the generic server endpoint:
for example, omitted `ageRating` defaults to zero and the scheduled-entry update
path can apply it
(`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:2205`;
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr_db.c:2565-2569`).

Reuse `DvrProgressResult.Accepted` / `Failed(TvheadendFailure, mayHaveApplied)`
and generation/access checks, but **do not locally gate on entry state or cache
membership**. Send the command and classify the actual reply; `SERVER_REJECTED`
is reserved for an actual server refusal, never a local missing-cache guess.
`NOT_SUPPORTED` is only a real protocol/command capability failure. A per-entry
failure must never poison the connection-wide `notSupported` latch or invoke
`onProgressNotSupported` in
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/session/DvrProgressCoordinator.kt:183-189,218-223`.
Expired observations return `OBSERVATION_EXPIRED` with `mayHaveApplied=false`.

All current `DvrEntryState` values are dispatch-eligible after those shared
checks (the complete set is at
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt:59-83`):

| Observed SDK state | Local decision | Checked upstream progress behaviour |
|---|---|---|
| `SCHEDULED` | Send | Success can leave both progress fields unchanged |
| `RECORDING`, `RECORDING_ERROR` | Send | Server recording state applies requested progress |
| `COMPLETED`, `COMPLETED_ERROR`, `FILE_MISSING` | Send | Server completed state applies requested progress |
| `MISSED`, `INVALID` | Send | Success can leave both progress fields unchanged |
| `UNKNOWN` (and future values) | Send | No inferred eligibility; only the reply is authoritative |

This is a dispatch table, not a guarantee that stale observed state still equals
server state. `Accepted` means the command received a successful reply, **not**
that requested fields changed. No optimistic snapshot update or confirmation
wait is promised; no-op SET may produce no metadata event (changes are emitted
only when `save` is nonzero at
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr_db.c:2648-2652`). An uncertain failure
must not be presented as a confirmed change. A later playback checkpoint can
overwrite a reset position; this command establishes no suppression of other
clients or active playback reporting.

**Open/closed and redaction:** input is a Boolean watch action;
the existing result is sealed with open `TvheadendFailure`. Its exact
`mayHaveApplied` contract is unchanged. Render captured requests as redacted and
failures as safe labels/boolean only.

**Tests and fakes:** gateway tests prove SET/RESET versus INCR, zero resume for
both actions, and standalone reset's zero position with absent play count,
protocol/access refusal and exact request fields. With an
injected gateway, prove pre-dispatch expiration, accepted reply followed by
retirement (`mayHaveApplied=true`), timeout/malformed reply, cancellation,
no-op acceptance and authoritative async updates. Add scripted results and
captured safe requests to `FakeDvrRepository` in `sdk-testing`; test consumer
actions against it. For every state in the table, prove dispatch rather than
local NOT_SUPPORTED/SERVER_REJECTED; simulate successful no-change replies.
Prove an explicit per-entry refusal does not disable a later progress command
for a different entry, while a genuine unsupported-protocol result still does.

**BREAKING:** adding abstract members to the existing public `DvrRepository`
requires implementors to change, despite the members' experimental annotations.
Record the `BREAKING` changelog note and migrate the in-repo implementation and
`sdk-testing`'s `FakeDvrRepository`
(`sdk-testing/src/main/kotlin/at/bernhardberger/tvheadend/sdk/testing/FakeTvheadendSession.kt:461-509`),
including result scripting/call capture. New fake API is experimental too.
No independent Player implementation of `DvrRepository` was found: its session
test stub merely throws from the property at
`/root/projects/tvheadend-player/client/src/test/java/at/bernhardberger/tvhplayer/settings/AppProfileOwnerTest.kt:430`;
its profiling/test sessions use the SDK fake, for example
`/root/projects/tvheadend-player/app/src/profile/java/at/bernhardberger/tvhplayer/profiling/JourneyProfileActivity.kt:89`.
Update that SDK-testing dependency and the Player's watch-action tests together.

**Player migration:** add Recordings actions using this command; delete no
existing playback-progress logic. Starting from the beginning is not a reset
command and must not silently acquire that meaning.
**Owner decision:** settled: both watched and unwatched clear resume implicitly.

## 5. Metadata refresh times

### Current state

Observations expose retained/current authority but no refresh instant:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/SessionObservation.kt:9-42`.
`EpgCoverage.queriedTo` proves a horizon, not when it was queried
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/EpgRepository.kt:201-244`).
Initial sync and successful EPG queries have distinct acceptance points:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/session/SessionResources.kt:778-797`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/session/EpgWorker.kt:330-343`.
Cache `storedAt` is disk-write time, not server refresh time
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/cache/MetadataCacheRuntime.kt:228-235`).
The Player currently shows a generic stale notice:
`/root/projects/tvheadend-player/app/src/main/java/at/bernhardberger/tvhplayer/ui/screens/EpgGridScreen.kt:1912-1917`.

### Proposal

In `sdk-core`, keep refresh bookkeeping **beside** content on `SessionObservation`:

```kotlin
@ExperimentalTvheadendApi
public data class MetadataRefreshTimes(
    public val lastSynchronizedAt: Instant? = null,
    public val lastUpdatedAt: Instant? = null,
)

// Members of SessionObservation, with empty/null defaults for existing factories:
@ExperimentalTvheadendApi
public val channelRefreshTimes: MetadataRefreshTimes
@ExperimentalTvheadendApi
public val epgRefreshTimes: MetadataRefreshTimes
@ExperimentalTvheadendApi
public val dvrRefreshTimes: MetadataRefreshTimes

@ExperimentalTvheadendApi
public val epgLastQueriedAt: Map<ChannelId, Instant>
```

Add experimental observation factory overloads with a required new freshness
argument for tests/consumers; keep existing overloads defaulting these fields.
The per-channel map is defensively copied and absence means unknown. Domain
state publication carries the companion values internally; there is no second
independently emitted flow to zip with content. Do not put time fields in
`EpgSnapshot` or `EpgCoverage`: snapshot equality/hash use `contentHash`
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/EpgRepository.kt:256-259`).
An accepted unchanged-data query reuses the same snapshot instance and hash,
updates only the companion times, and emits a new observation through its
freshness-aware equality. A widened coverage horizon still changes content.

These are local receipt/acceptance times from an injected wall clock, not
timestamps claiming when the broadcaster or server acquired its guide. Normalize
to epoch-millisecond precision on acceptance, matching cache storage.

- Initial-sync completion stamps `lastSynchronizedAt` and `lastUpdatedAt`,
  including empty snapshots. Partial pushes advance only that domain's
  `lastUpdatedAt`; a successful accepted EPG query also advances EPG update
  time and its channel's `epgLastQueriedAt`, including an empty reply.
- Do not advance on request start, failed/malformed/cancelled replies, rejected
  capacity admission, old generations, local pruning or display republication.
  A query that returns unchanged data still refreshes its accepted-query time.
- Keep timestamps with the exact retained snapshot on disconnect/reconnect;
  clear them with profile teardown. Publish them atomically with metadata and
  include them in **observation** equality, never snapshot equality/contentHash.
- Persist companion values with cached channel/EPG content and restore their
  original times, never load time or `storedAt`. Use internal cache payloads
  pairing content and times, not new public snapshot fields. DVR remains
  in-memory; this item does not introduce DVR disk caching.
- No single timestamp proves the whole guide fresh. The Player pairs authority,
  `knownTo`/coverage and `epgLastQueriedAt[channelId]` for the displayed channel/range. Null
  means unknown, not never refreshed; empty plus a recent successful covered
  query means genuinely queried-empty. The app chooses its stale-age threshold.
  Clock corrections can move wall times backwards; ages displayed by the app
  clamp at zero, and timestamps never authorize generation-bound actions.

**Concrete cache migration:**

- Catalog: keep `CATALOG_SCHEMA_VERSION = 1`. Add nullable defaulted envelope
  fields `@ProtoNumber(4) lastSynchronizedAtEpochMillis: Long? = null` and
  `@ProtoNumber(5) lastUpdatedAtEpochMillis: Long? = null` to `CatalogEnvelopeDto`.
  Existing protobuf files decode those absent fields as null, retaining usable
  catalog content without inventing freshness. Existing fields/numbering are at
  `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/cache/CacheDtos.kt:27-40`.
- EPG: bump `EPG_STREAM_MAGIC` from **EPG2 `0x45504732` to EPG3 `0x45504733`**;
  invalidate EPG2 files through the existing mismatch/delete path. After
  `storedAt`, add one length-prefixed protobuf domain-times record; append a
  bounded sequence of per-channel query-time protobuf records after coverage.
  Reuse the existing record framing/bounds; do not write another codec. The
  format is a binary stream, not an optional protobuf envelope:
  `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/cache/FileMetadataCacheStore.kt:28-31,68-94,109-129,214-229`.
  Choose invalidation because framing changes and cache data is reconstructible;
  it avoids maintaining two binary readers. The first offline start after the
  bump has no cached EPG until it is refetched; catalog retention is unaffected.

**Open/closed and redaction:** closed timestamp payload, existing closed
authority states; no I/O API or new failure result. Only instants are printable;
containing metadata snapshots remain redacted.

**Tests and fakes:** constructor-injected wall clock and gateway; prove empty
sync, empty/unchanged successful query, partial update scope, rejection,
retirement, cache restore, profile switch and equality emission. Specifically
assert identical snapshot reference/contentHash after an unchanged-data reply,
catalog-v1 absent-field nulls, EPG2 invalidation, and EPG3 time/content roundtrip.
Extend
`sdk-testing` observation fixtures to set refresh times and per-channel query
times, preserving null defaults for old tests.

**Player migration (new information, not a workaround replacement):** add age
to the generic guide notice cited above; keep connection notices, empty-guide
presentation and coverage/stale-age policy. Do not claim an existing timestamp
guess implementation. **Size:** 12–17 files, split observation/publication then
cache/fake coverage.
**Owner decision:** no; report facts and leave stale-age presentation policy
with the app.

## 6. Recording stream information

### Current state and wire facts

TVHeadend writes an ordered `files[].info` list containing codec `type`,
optional language, audio type/version, width/height, frame duration, aspect
ratio and subtitle ids:
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/dvr/dvr_rec.c:1458-1534`.
HTSP copies this recording-log metadata into `dvrEntry`:
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:1130-1146`.
The sample-rate/channel strings in that recorder code are log output, not
additional fields in `info`; do not promise them.

HTSP already parses every listed field, preserving malformed element positions
as empty observations and converting 90 kHz frame duration to microseconds:
`/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/HtspDvrMessages.kt:6-47`;
`/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/HtspServerMessageDecoders.kt:540-571`.
Read-only `javap` inspection of the cached `htsp:1.0.0-beta.1` jar also confirms
`HtspDvrRecordingFile.getInfo()`, `HtspDvrRecordingInfo.getStreams()` and the
stream field getters; the pinned artifact exposes the needed model.
It is dropped at `HtspDvrRecordingFile.toGatewayFile`, before the reducer:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:1611-1617`.
Neither the internal file nor public file has a stream field:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/MetadataEvent.kt:391-399`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt:140-164`.

The Player's playback diagnostics obtain format details from Media3:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/PlaybackPresentationPublisher.kt:282-309`.
Those measurements remain useful; recorded-file metadata enables details
before opening playback.

### Proposal

In `sdk-core`:

```kotlin
@ExperimentalTvheadendApi
public data class DvrRecordingStream(
    public val type: SubscriptionStreamType = SubscriptionStreamType.UNKNOWN,
    public val language: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
)

// Add to DvrRecordingFile, with an experimental factory overload:
@ExperimentalTvheadendApi
public val streams: List<DvrRecordingStream>? = null
```

Expose the requested codec/language/resolution subset now; other parsed fields
can be appended with defaults when consumed. Reuse the existing app-safe open
codec set and gateway mapping, rather than introduce a competing codec enum.
Absent `info` becomes null; an explicit empty list stays empty; unknown or
malformed stream entries retain their list position with `UNKNOWN`/null fields.
Map dimensions outside `1..Int.MAX_VALUE` to null. Keep width and height
independent; the Player displays a resolution only when both are present.

Keep information per file; do not claim all files share one format. Preserve
stream metadata across omitted partial file updates, and replace it when a new
authoritative files list arrives. Snapshot-copy lists at gateway/public factory
boundaries. Do not open the file or parse media containers for this feature.

**Open/closed and redaction:** `SubscriptionStreamType` is open, with unknown
fallback; payload structure is additive. Language is display-only untrusted
server text and is never rendered by `toString()`. File paths remain internal.
No round trip, new failure or mutation is introduced.

**Tests and fakes:** gateway mapping tests for multiple files, unknown codec,
missing/empty `info`, placeholder positions and dimension limits; reducer tests
for partial update/completion and immutable snapshots. Add streams to
`sdk-testing` recording fixtures using the public file factory, including an
audio-only recording. Player tests render these fixtures without Media3.

**Player migration (new metadata, not a decoder replacement):** consume
`entry.files[].streams` for recording details; keep the cited real playback-format
diagnostics. No Player deletion is required. **Size:** 6–9 files. **Owner decision:** no; the wire data
and the point where it is lost are verified.

## 7. Typed radio versus TV classification

### Current state and wire facts

The SDK already carries `ChannelService.type: String` and `content: Long`,
but no typed classification:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/ChannelRepository.kt:37-71`.
Upstream emits classification per **service**, not as a channel-level radio
boolean. `services[].content` is 0 other, 1 TV, 2 radio; `type` is a descriptive
string such as UHD/HD/Radio:
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:895-921`.
It is emitted **unconditionally for every service in that list**, using
`service_is_tv(t) ? 0x01 : (service_is_radio(t) ? 0x02 : 0x00)` at
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:903-904`.
TV is the union of SD/HD/FHD/UHD predicates
(`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/service.h:435-443`).
Those predicates consult the user override/server service type, falling back
for `ST_NONE` to scanned video dimensions; radio's fallback requires audio and
no video:
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/service.c:859-958`.
Consequently zero means **server classified it as neither TV nor radio
(possibly unscanned)**, not proof of known non-TV content.

**Missing-field check:** HTSP uses `content = requiredU32("content")` at
`/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/HtspServerMessageDecoders.kt:528-537`;
the required reader rejects a missing/non-numeric value, rather than supplying
zero (`/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/wire/HtspFieldReader.kt:13,41-48`).
The SDK directly assigns that non-null value, without defaulting:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:1470-1477`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/MetadataEvent.kt:311-319`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/ChannelRepository.kt:37-45`.
Thus **absence is not collapsed to zero in this path**. No new presence carrier
is needed for the current dependency. A missing `content` inside a service is
currently a malformed-message case, not a successfully decoded UNKNOWN service.
Do not promise otherwise or write a second HTSP decoder. If a dependency later
accepts absent content, its nullable/presence value must be carried through the
internal service mapping and produce UNKNOWN, never `?: 0`/OTHER.
The Player's current audio-only presentation instead checks selected playback
tracks:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/playback/AppPlaybackRuntime.kt:458-459`.
That runtime fact is distinct from a catalog classification.

### Proposal

In `sdk-core`:

```kotlin
@ExperimentalTvheadendApi
public class ChannelMediaKind private constructor(/* label */) {
    public companion object {
        @JvmField public val TV: ChannelMediaKind
        @JvmField public val RADIO: ChannelMediaKind
        @JvmField public val OTHER: ChannelMediaKind
        @JvmField public val UNKNOWN: ChannelMediaKind
    }
}
@ExperimentalTvheadendApi public val ChannelService.mediaKind: ChannelMediaKind
@ExperimentalTvheadendApi public val Channel.mediaKind: ChannelMediaKind
```

Use derived properties over existing service data, requiring no new wire field
or cache schema. Service content maps exactly 0/1/2; any other value is UNKNOWN.
A channel is TV if any service is TV; otherwise RADIO if any is radio;
otherwise OTHER only if a non-empty service list is entirely OTHER; otherwise
UNKNOWN. TV takes precedence for mixed radio/TV mappings because the channel
can deliver video. Do not parse labels, channel names or tags as a fallback.

**Open/closed and redaction:** open singleton set because server service
classifications can grow. Consumers keep a fallback; OTHER records the server's
neither-TV-nor-radio classification (possibly unscanned), UNKNOWN is insufficient
or unrecognized evidence. Labels only in `toString()`.
No I/O outcome is needed.

**Tests and fakes:** service values 0/1/2/future, null/empty services, mixed
TV/radio, known plus unknown and partial service replacement. Existing gateway
fake mapping tests verify `content` preservation. Add TV/radio/unknown catalog
fixtures to `sdk-testing`; no new fake interface. Gateway-layer tests using
the pinned HTSP decoder must distinguish an absent entire services list (no
classification evidence) from a service with missing required `content`
(mapping failure), and ensure neither invents OTHER. Above the gateway, use
normal metadata fakes; do not fake `HtspConnection` there.

**Player migration:** use `channel.mediaKind` for browse/filter/icons. Keep
selected-track audio-only detection for the actual playing item; it must not
be replaced by a catalog hint. This adds a catalog classification rather than
replacing that runtime check; no Player deletion is required.
**Size:** 4–6 files. **Owner decision:** no; the deterministic
aggregation is documented and introduces no product filtering policy.

## 8. Experimental series-rule upcoming recordings

### Current state and wire facts

TVHeadend includes `autorecId` and `timerecId` when an entry is linked to its
rule:
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:987-994`.
HTSP parses both on add/update:
`/root/projects/tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/HtspServerMessageDecoders.kt:99-100,153-154`.
The SDK already exposes `DvrEntry.autorecRuleId` and `timerecRuleId`
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt:169-177`).
Observation helpers currently join entries to EPG events, not rules:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/SessionObservation.kt:102-121`.
The Player already groups DVR notices by autorec id:
`/root/projects/tvheadend-player/client/src/main/java/at/bernhardberger/tvhplayer/notices/DvrChangeNoticeSource.kt:16-31`.
This supplies the planned rules list; the cited notice grouping remains.

### Proposal

In `sdk-core`, under the already decided experimental tier:

```kotlin
@ExperimentalTvheadendApi
public fun SessionObservation.upcomingRecordings(
    ruleId: AutorecRuleId,
    from: Instant,
    until: Instant,
): RuleUpcomingRecordings

@ExperimentalTvheadendApi
public class RuleUpcomingRecordings internal constructor(/* immutable values */) {
    public val entries: List<DvrEntry>
    public val authority: RetainedMetadataAuthority
    public val rulePresent: Boolean
}
```

Require `until > from`. Select the exact observation's enabled (not explicitly
disabled), `SCHEDULED` entries linked by `autorecRuleId`, with known start in
`[from, until)`. **Known start** means non-null `DvrEntry.start`, the scheduled
wire `start`, not `start - startExtra`; upstream emits these separately at
`/root/.local/share/opencode/repos/github.com/tvheadend/tvheadend/src/htsp_server.c:996-998`.
Exclude in-progress `RECORDING`/`RECORDING_ERROR` even if their start lies in the
window, and exclude null starts. Sort by scheduled start then entry id. Do not join on title, regex,
series link or EPG event id. Keep the server's scheduled entries even when a
duplicate flag is present; do not second-guess its scheduler.

`authority` is that snapshot's DVR authority, not the EPG's. `rulePresent`
distinguishes an observed rule with no scheduled entries from a missing rule;
ABSENT authority means no usable metadata, not an authoritative zero count.
Retained results are display-only. An orphaned entry may still match its stored
rule id while `rulePresent=false`; do not silently discard it. Rule mutation
actions still require a fresh `CurrentSessionObservation`.

This is a local immutable selector: no server round trip or typed failure is
needed. It reports scheduling results, not all future EPG candidates or the
reason an event was excluded. A timerec overload can be added later using its
verified link, once the Player needs it; no common rule-id union is necessary.

**Open/closed and redaction:** additive, value-equal result payload; existing
closed authority model and open DVR states. The experimental annotation is on
both helper and result. Result `toString()` is redacted, including nested titles
and rule identifiers.

**Tests and fakes:** local synthetic snapshots prove rule isolation, missing
rules, stale/absent authority, bounds, stable ordering, disabled/completed/
unknown/in-progress states, null starts, nonzero `startExtra` without shifting
the interval, orphans and absent EPG. Use gateway fakes only for the upstream
link-preservation integration test. Extend `sdk-testing` with linked rule/entry
fixtures; no simulated rule matcher or server request.

**Player migration (new helper):** the Recordings rules list calls the selector
and labels the result “upcoming recordings”. No current filter replacement is
claimed. Keep the existing notice grouping cited above.
**Size:** 4–6 files. **Owner decision:** yes; scheduled results versus all
potential EPG matches is a consequential product/API distinction.

## 9. More than one coordinator per session

### Current actual behaviour

The factory takes a Player, not a session, and returns a fresh coordinator with
its own command queue and target state. There is no session-level coordinator
registry or exclusivity check:
`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:446-464,895-919`.
The session owns one subscription manager per connection generation:
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/session/SessionSubscriptions.kt:341-350`.
That manager holds a map of active subscriptions and allocates a new handle for
each open, rather than restricting the session to one subscription:
`sdk-playback/src/main/kotlin/at/bernhardberger/tvheadend/sdk/playback/SubscriptionStateMachine.kt:535-610`.

Thus multiple coordinators on distinct Players can mechanically share one
session; server/tuner/account limits still apply, and connection/profile teardown
retires all generation-bound work. Multiple coordinators over the **same**
Player are not coordinated: one can replace another's current item, causing
departure under the item-ownership rules
(`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:430-443`).
They do not provide playlist, progress-write or recovery arbitration.

The Player currently builds one Player and one coordinator in
`/root/projects/tvheadend-player/app/src/main/java/at/bernhardberger/tvhplayer/di/AppModule.kt:90-125`.

### Documentation decision

Document **one active coordinator/Player pair per session as the supported
Android TV 1.0 topology**. Multiple distinct-Player pairs are not blocked by
the implementation, but are not a supported concurrent-playback guarantee;
sharing one Player between active coordinators is unsupported. Do not claim
that construction of a second coordinator throws or evicts the first. Android
TV media picture-in-picture does not require expanding this scope.

No new signature, open/closed type, typed outcome or redaction surface.
No runtime enforcement is introduced by this documentation decision.
**Tests and fakes:** no new behaviour test or `sdk-testing` API is needed for
the documentation slice; retain ownership/departure and multi-subscription
tests. Any future support guarantee needs explicit two-Player lifecycle,
resource-limit and conflicting-recording-progress tests before it is made.
**Player migration:** none. **Size:** 2 documentation/KDoc files.
**Owner decision:** no; this records the tested product scope and actual
implementation limits without promising new concurrency support.

## Verification and remaining uncertainty

The citations above were checked against the local SDK, Player, HTSP and
TVHeadend source checkouts. They establish wire shape and implementation paths,
not an acceptance run against a deployed server.

- **Dependency evidence limit:** the cached pinned HTSP artifact exposes the
  required recording-info model, and the local source contains the parser.
  Bytecode inspection did not exercise decoding; gateway fixtures must prove
  the pinned artifact's decoder behaviour before accepting item 6.
- **Unverified compatibility range:** the upstream checkout confirms the
  fields and semantics above. It does not establish the oldest deployed
  TVHeadend version that emits recording `info`, service content or rule links.
  Missing optional metadata must retain the documented unknown/null behaviour;
  missing required service `content` is a mapping failure as described in item 7. HTSP's
  request gate verifies protocol 27 for watch-state writes, not a complete
  server compatibility matrix.
- **Hypothesis, not support evidence:** distinct-Player concurrent coordinators
  should work within server limits because their local state and subscription
  handles are separate. No two-Player acceptance evidence was collected, and
  this note does not turn that hypothesis into a support promise.
- **Media3 bridge evidence limit:** the pinned source confirms the stable
  builder boundary and rebuffer fields. The proposed handle has not been
  compiled or exercised; its no-opt-in consumer compile and forwarding/skip
  tests remain required before accepting item 3.

For implementation, each slice includes focused module tests, consumer-contract
usage/fakes, and maintained ABI generation where supported. Then run the
repository's normal offline gates via `tools/verify`, and obtain the required
independent review for public API, lifecycle and playback changes. Resolve
items 4 and 8 before implementing them. No build, server or device validation
was performed while drafting this note.
