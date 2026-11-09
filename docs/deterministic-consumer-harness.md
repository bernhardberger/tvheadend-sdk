# Deterministic consumer playback tests: phase 1 proposal

Status: slice 1 (live path) accepted by the owner on 2026-11-09 and merged.
The consumer recipe is in the consumer guide, "Test live playback
deterministically". Slice 2 (remaining timers) is implemented on branch
`feat/deterministic-recording-harness`; see "Slice 2 as implemented". On the
same branch, `sdk-testing` adds `FakeGrowingRecording` so that a consumer test
controls a recording that is still being written; the consumer guide describes
it in "Test a recording that is still being written". Requested by the Player
test-quality work.

## Problem

Player playback tests run a real ExoPlayer against the SDK's real coordinator,
live source and subscription manager, with `sdk-testing` fakes for the
connection. The test controls the Media3 application looper and its own
coroutine scheduler, but the SDK moves work onto threads the test cannot see:

- the live source opens and consumes its subscription on `Dispatchers.IO`;
- the coordinator's posts, the live source's callbacks and the recovery
  watchdogs use plain `Handler`s, outside the player's `FakeClock`;
- the application cannot pass a dispatcher to `createTvheadendPlaybackCoordinator`.

So a test has to wait in real time for a condition such as "the replacement's
timeshift grant is published". Under host load the test moves on too early and
flakes. Under mutation testing, a mutant that breaks the condition makes every
such wait run its full real-time budget. That is why about 1,300 PIT minions
timed out in 12 minutes.

## Inventory of the consumer playback path

Paths are relative to `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/`
unless stated otherwise. Line numbers refer to `origin/main` 28978f3.

| Where | Thread, executor or timer | Injectable on main | After prototype |
|---|---|---|---|
| `TvheadendLiveMediaSource.kt:62-73,131` open, consume result, bind period, `subscriptionOpened` | `CoroutineScope(Dispatchers.IO)` | internal ctor only | `workDispatcher` on both public coordinator factories |
| `TvheadendLiveMediaSource.kt:294` drain; `:333` `refreshSourceInfo` post | same scope; `Handler(Looper.myLooper())` on the playback thread | no | dispatcher; handler from the player's `Clock` |
| `TvheadendLiveMediaPeriod.kt:53-61,95,478,504` sample work, alternative-format wait (`delay(1 s)`), playback-thread posts | inherits the source's dispatcher; plain `Handler` | no | inherits; `Clock` handler |
| `LiveTimeshiftControls.kt:471,940-982` grant and state publication | runs on the caller's thread (the source worker) | follows the source | follows the source |
| `PlaybackCoordinatorInternals.kt:188` `HandlerCoordinatorLooper` (coordinator → application looper) | plain `Handler` | no | `Clock` handler |
| `TvheadendPlaybackRecovery.kt:142,232` watchdogs (6 s, 6 s, 20 s) | `Handler.postDelayed` on the application looper | internal `RecoveryScheduler` only | `Clock` handler when created by the coordinator |
| `TvheadendRecordingResume.kt:351,372` resume timeout | `Handler.postDelayed` | no | slice 2: `Clock` handler |
| `GrowingTsExtractor.kt:98` extent probe; `GrowingTsExtent.kt:42` 5 s timeout | `CoroutineScope(Dispatchers.IO)` hard-coded | no | slice 2: `workDispatcher` |
| `TvheadendRecordingDataSource.kt:110`, `TvheadendGrowingRecordingDataSource.kt:72` | `runBlocking` on Media3 Loader threads | no; `ProgressiveMediaSource.Factory.setDownloadExecutor` exists but is not exposed | unchanged, see limits |
| `LiveStartLoadControl.kt:22` live-start timing | `android.os.SystemClock.elapsedRealtime` on the playback thread | no | unchanged: under Robolectric `FakeClock` moves `SystemClock` |
| `TvheadendPlaybackCoordinator.kt:1077` DVR progress timestamps | `Clock.System` (wall clock); waits use `delay` in the consumer scope | no | slice 2: player's `Clock` (elapsed time) |
| `TvheadendPlaybackCoordinator.kt:1494,1545` drain, paused-seek polling | consumer coroutine context | yes (consumer scope) | unchanged |
| `TvheadendAudioOutputProvider.kt:64` 2 s wait for a playback-thread message | caller's coroutine context | yes | unchanged, see hazards |
| `sdk-playback/.../SubscriptionStateMachine.kt:484` manager, seek/restart timeouts (5 s) | `createSubscriptionManager(connection, dispatcher)` | **yes, public** | unchanged |
| `sdk-core/.../TvheadendSession.kt:197-265` production session, gateway, profile owners | `Dispatchers.IO` / `Default` | not needed: tests use `FakeTvheadendSession` | unchanged |

Media3 keeps these on real threads whatever the SDK does: the playback thread
(`ExoPlayer.Builder.setPlaybackLooper` rejects the main looper), Loader
executors for progressive (recording) loads, and the audio sink's release
thread. None of them run SDK consumer logic on the live path once the prototype
is in place. They are acknowledged through Media3's own signals
(`TestPlayerRunHelper...untilPendingCommandsAreFullyHandled`). That wait
depends on the playback thread handling a message, not on any SDK condition, so
a broken SDK condition cannot stretch it.

## Options

**(a) Injectable dispatcher plus the player's clock.** Add one public
`workDispatcher` parameter to the coordinator factories and pass it to the live
source and period. Create every SDK handler on the player looper or playback
thread through `ExoPlayer.getClock().createHandler(...)`. In production
`Clock.DEFAULT` creates an ordinary handler, so behaviour is unchanged. In a
test, a non-advancing `FakeClock` holds SDK timers until the test advances it.
Its message ordering serializes SDK posts with the player's own messages.
Cost: one public parameter per factory, no new types. Limit: two virtual clocks
(`TestCoroutineScheduler` and `FakeClock`) that the test advances together.

**(b) A harness type that builds player, manager and coordinator.** This is
convenient, but the lockstep helper needs `FakeClock` from `media3-test-utils`.
`sdk-media3` main must not depend on test utilities, and `sdk-testing` must
stay pure JVM, so it would need a new published module. It also fixes how the
Player wires its own fixtures (profile owner, settings, audio focus). Defer it
until (a) has been piloted.

**(c) Public readiness signals** (open consumed, prepare failed). These help a
test wait for the right moment but keep the real thread, so the wait still has
a real-time budget. They would have to become permanent API for one test need.
Once (a) is in place, the existing observable outcomes (`timeshiftState`,
`subscriptionIssue`, player errors) can be asserted directly after a settle.
One exception remains: a live preparation failure is visible only as the
player's error, which is already observable. No new signal is needed.

## Recommendation and API sketch

Adopt (a). Document the test recipe for consumers. Reconsider (b) only if the
Player pilot shows the recipe is repeated in many places.

```kotlin
/**
 * Creates a narrow playback coordinator over an application-owned [player].
 *
 * Live sources run their subscription work on [workDispatcher]. Their callbacks, and the
 * coordinator's recovery timers, use handlers of [player]'s clock, so a Media3 `FakeClock`
 * orders them with the player's own messages. A test that passes a `StandardTestDispatcher`
 * and a non-advancing `FakeClock` decides when each live SDK step runs; no live SDK step then
 * waits on a real thread. Media3's own playback thread and loaders stay real threads.
 */
public fun createTvheadendPlaybackCoordinator(
    player: ExoPlayer,
    progressPolicy: DvrProgressPolicy = DvrProgressPolicy(),
    recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
    onRecoveryRequired: (PlaybackRecoveryReason) -> Unit = {},
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
    workDispatcher: CoroutineDispatcher = Dispatchers.IO,
): TvheadendPlaybackCoordinator
```

The same parameter goes on the `loadControl` overload. Slice 2 needed no
further public parameter; see "Slice 2 as implemented".

Consumer recipe (test-side, about 15 lines in the consumer):

```kotlin
val work = StandardTestDispatcher(testScheduler)
val clock = FakeClock(/* isAutoAdvancing = */ false)
val player = TestExoPlayerBuilder(context).setClock(clock).build()
val manager = createSubscriptionManager(connection, work)
val coordinator = createTvheadendPlaybackCoordinator(player, workDispatcher = work)

fun ExoPlayer.handleCommands() {           // Media3's acknowledgement, not an SDK condition
    TestPlayerRunHelper.advance(this).untilPendingCommandsAreFullyHandled()
    shadowOf(Looper.getMainLooper()).idle()
}
fun TestScope.settle() = repeat(8) { runCurrent(); player.handleCommands() }
fun TestScope.advanceBy(d: Duration) {     // both clocks in lockstep, in short steps
    var left = d
    while (left > Duration.ZERO) {
        val step = minOf(left, 100.milliseconds)
        clock.advanceTime(step.inWholeMilliseconds); advanceTimeBy(step); settle()
        left -= step
    }
}
```

After `settle()` the test asserts outcomes directly. It never waits for an SDK
condition.

**Compatibility.** Adding a defaulted trailing parameter changes the JVM
signature and breaks source calls that pass `onUnsupportedStream` as a trailing
lambda, which would now bind to `workDispatcher`. Such calls name the argument
instead. Both breaks are acceptable in 0.x and are marked BREAKING in the
changelog. Android ABI dumps are not generated with the pinned
toolchain (KT-83410), so no dump changes. A parameter cannot carry an opt-in
marker. If the owner wants the dispatcher gated, the alternative is a separate
`@TvheadendTestingApi` overload. The recommendation is the plain parameter,
matching `createSubscriptionManager(connection, dispatcher)`. Production
behaviour does not change: `Clock.DEFAULT.createHandler` wraps a plain
`Handler`.

## Proof

`sdk-media3/src/test/.../ExoPlayerSourceReplacementTest.kt`, test
``a live target is timeshift-ready only after its open has been consumed``. It
extends the existing real-ExoPlayer coordinator test and uses only public
consumer API plus the existing fakes: `createTvheadendPlaybackCoordinator`,
`createSubscriptionManager`, `FakeTvheadendSession.scriptLivePlaybackSuccess(manager)`,
`ScriptedSubscriptionConnection`, `TestExoPlayerBuilder` with `FakeClock(false)`.

It holds the Player's race state on purpose. After the target is installed and
the playback thread has prepared the source, the server has not been asked
(`subscribeCount == 0`) and `timeshiftState` is `Unavailable`. Real threads
cannot change that, however long they run. Then the test runs the queued open,
emits `Started` and a timeshift status, settles, and asserts `Available` with
the 120 s grant and no recovery request. Neither clock is advanced.

| Run | Result | Test time |
|---|---|---|
| Prototype, 4 runs | pass | 0.13–0.15 s |
| Pre-fix behaviour (live source back on `Dispatchers.IO`), 3 runs | fail: still `Unavailable` when asserted, the Player's race | 0.11–0.13 s |
| Mutant: open result treated as late (`error != null` negated), consumption skipped | fail | 0.135 s |
| Mutant: `isCurrent` passed to `subscriptionOpened` negated | **survives** | 0.14 s |

Both failing cases fail in about 0.1 s, not after a real-time budget. The
surviving mutant also comes back quickly: under PIT it would count as
`SURVIVED` (a coverage gap to look at), not `TIMED_OUT`. That predicate guards
priority reconciliation for a superseded opener, which this scenario does not
exercise.

`./tools/verify :sdk-media3:check` passes (unit tests, detekt including test
sources), and `:sdk-media3:compileDebugAndroidTestKotlin` compiles.

## Hazards and limits

- **runTest auto-advance across a real-thread handoff.** If every coroutine is
  suspended while the playback thread still owes a reply, `runTest` advances
  virtual time to the next timeout. One example is
  `TvheadendAudioOutputProvider`'s 2 s wait. Pump the player
  (`handleCommands`) before yielding to the scheduler.
- **Two clocks.** The scheduler and `FakeClock` advance only together through
  `advanceBy`. Advancing one alone skews SDK timers (1 s alternative-format
  wait on the scheduler, recovery watchdogs on `FakeClock`).
- **Recordings.** Progressive loads keep Media3's Loader thread and the SDK's
  `runBlocking` there. A direct download executor would block the playback
  thread on scheduler work and deadlock. Recording tests keep the loader real
  and wait on Media3's signals. They cannot become fully virtual without a
  reader redesign, which is not proposed.
- `settle` uses a fixed round count, not a quiescence test. Eight rounds cover
  the live open-to-timeline path. A scenario that needs more rounds should
  advance or settle again explicitly rather than raise the count silently.

## Slice 2 as implemented

Player-looper timers use the player's `Clock`, coroutine work uses
`workDispatcher`, and no public parameter is added.

- Recording resume timeout: handler from the player's `Clock`.
- Standalone `createTvheadendPlaybackRecovery`: handlers from the `ExoPlayer`'s
  `Clock`; another `Player` keeps `Clock.DEFAULT`.
- Growing-recording extent probe: `workDispatcher` when the coordinator creates
  the source; `Dispatchers.IO` otherwise.
- DVR progress cadence: both the timestamps and the checkpoint wait use the
  player's `Clock`. Every consumer of the coordinator's time source was checked
  first: `TvheadendPlaybackCoordinator.kt` passes `now()` to
  `DvrProgressTracker.onPause` and `onElapsed` and waits one checkpoint interval
  between them; the tracker (`sdk-core/.../DvrProgress.kt:171-190`) only
  compares two of its values. None reads `now()` as a wall time, so all of them
  moved. `now()` is elapsed realtime and is never persisted, reported or
  compared with wall time.
- Live-start load control: unchanged. Its skip window compares
  `SystemClock.elapsedRealtime` with Media3's rebuffer timestamps from the
  player's `Clock`. In production that is the same clock. Under Robolectric,
  `FakeClock` sets `SystemClock` when it is created and on every advance, so the
  two agree as long as nothing else moves Robolectric's clock. Running the main
  looper task by task does: it advances `SystemClock` to the next delayed
  message, past `FakeClock`, and the skip window then starts late. The harness
  tests could not observe a difference, so the load control was left on
  `SystemClock`. If the Player pilot meets that drift, the `loadControl`
  coordinator factory can bind the load control's existing `elapsedRealtimeMs`
  parameter to `player.clock`.

Media3's loader threads still load recordings. Regression tests:
`PlayerClockTimersTest` (standalone recovery, recording resume, DVR cadence on a
real `ExoPlayer` with a non-advancing `FakeClock`) and the growing probe case in
`GrowingTsIndexTest`, now on virtual time. Each fails with its change reverted.

## Cost in slices

1. **Dispatcher and clock routing for the live path.** Done in the prototype
   (about 50 lines of main code and one test). For merge: changelog entry,
   consumer-guide section with the recipe, independent review (public factory
   signature, threading). About half a day.
2. **Remaining timers.** Load-control clock, coordinator wall clock, recording
   resume timers, growing-recording probe. Each one extends the existing unit
   test of that component. About one day.
3. **Optional: a published lockstep helper module**, only if the pilot shows
   the recipe is duplicated. It needs owner approval for a new module. Not
   estimated.

Per release cadence, this ships in the next staged candidate the Player needs,
after the Player pilot accepts it.

## Player pilot

Convert the focus-loss-after-replacement case in `AudioInterruptionRuntimeTest`
first. That is the race fixed in Player 06602a8c ("wait for the replacement's
timeshift grant before a focus loss") and the scenario the proof models.

- Fixture: replace the counting `managerDispatcher` over `Dispatchers.Default`
  with `StandardTestDispatcher(testScheduler)`. Pass the same dispatcher as
  `workDispatcher` to the coordinator. Keep `FakeClock(false)`. Pass a test
  dispatcher to `AppProfileOwner` instead of `Dispatchers.IO` (Player-side
  change).
- Delete `ControlledOpener` and `PendingOpen`. The open is held simply by not
  running the scheduler, and "consumed" is the observable `timeshiftState`.
- Replace `awaitReplacementTimeshift` and the `PlaybackTestDriver.await`
  real-time budgets for SDK conditions with `settle()` followed by plain
  assertions. Keep `settle(player)`'s Media3 message round trip.
  `exercise(...)` keeps its `runTest` timeout only as a deadlock backstop.
- Measure: PIT on the converted class before and after, counting `TIMED_OUT`
  minions and wall time. Convert the remaining `AudioInterruptionRuntimeTest`
  cases, then `SessionPlaybackPlayerTest`, `LiveStartBufferPlayerTest` and
  `BackgroundPlaybackRuntimeTest`. `RecordingRecoveryPositionTest` waits for
  slice 2 and keeps the Loader limit above.

## Owner decisions (2026-11-09)

1. `workDispatcher` is a plain trailing parameter on both coordinator
   factories. There is no `@TvheadendTestingApi` overload.
2. SDK posts and timers on the player looper use the player's `Clock` in
   production.
3. The recipe goes in the consumer guide only. A helper module (slice 3) is
   considered only if the Player pilot shows repetition.
4. Slice 2 follows the Player pilot and targets the same 0.39 line before its
   release. It is not part of slice 1.
