# Timeshift Consumer Tests

`at.bernhardberger.tvheadend.sdk.media3.testing.TimeshiftTestFixture` is an
SDK-owned host-test fixture in `sdk-media3`, introduced in candidate `0.6.1`.
It is not in published `0.6.0`; consume it only after `0.6.1` is verified. It needs no Player,
Android runtime, server, reflection, infrastructure opt-in or dependency
substitution. `sdk-testing` remains pure JVM and does not depend on Media3.

The fixture produces the same opaque timeline, target, estimate and completed
result types used by the coordinator. Its targets are scoped to one synthetic
subscription and cannot control a real coordinator or another fixture.

```kotlin
val fixture = TimeshiftTestFixture(grantedPeriod = 120.seconds)
fixture.updateHistory(start = 10.seconds, end = 100.seconds)
val available = fixture.state.value as LiveTimeshiftState.Available
val selected = requireNotNull(available.timeline).select(40.seconds)!!

// Advance history without retargeting the selection.
fixture.updateHistory(start = 20.seconds, end = 110.seconds)
val pendingReply = CompletableDeferred<TimeshiftContentSeekResult.Completed>()
val seek: suspend (TimeshiftContentTarget) -> TimeshiftContentSeekResult = { target ->
    fixture.seek(target) { pendingReply.await() }
}
// Inject seek and fixture.state into the application's existing test boundary.
// Launch the application interaction, advance its test scheduler, assert dispatch
// order, then release exactly the reply that this dispatch should receive:
pendingReply.complete(fixture.completed(readerReached = 39.seconds))
```

Use separate deferred replies or a constructor-injected callback recorder in the
app test to exercise 400 ms coalescing, immediate commit, and in-flight dispatch
ordering. The fixture intentionally has no timer, command queue, target clamping
or coalescing policy: otherwise app ordering defects could be hidden by its fake.
Observation normalization mirrors production.
Cancellation of `seek` propagates through the dispatch callback. The callback
must cooperate with cancellation, just like any suspend test dependency.

- `updateHistory` changes observed bounds, not capacity. Missing bounds model
  temporarily unavailable history. Negative bounds can model buffered but
  unselectable content; out-of-range selection returns null. Reader shift is
  unavailable without a buffer and clamped to the observed buffer otherwise.
- `seek` shares production target validation: a retained target outside current
  history returns `Expired` without invoking dispatch; missing history returns
  `Unavailable`; an old or foreign subscription returns `Replaced`.
- `replaceSubscription` resets history and fences old targets, including an
  already-dispatched result. `endSubscription` also removes availability and
  ignores late history observations. `state` is not a subscription-epoch signal:
  equal history-free values conflate, as they do in production.
- `completed(command, readerReached)` scripts accepted, rejected, uncertain or
  terminal feedback. Reached coordinates are allowed only for `ACCEPTED` results;
  null explicitly models an unknown reader position. Terminal dispatch results
  end the synthetic subscription. A reached target from another subscription is
  rejected as an invalid test setup, not silently rebound.
  A reply can still be constructed after end so the pending seek returns
  `Replaced`. A terminal reply commits termination before late caller cancellation
  propagates. This is a boundary fixture, not an exhaustive protocol simulator;
  other public command outcomes can be scripted for application fallback tests.
- `playbackPosition(position)` creates an estimate independently of server-reader
  movement. Null means unavailable. Keep a previous estimate while advancing
  history to test paused or queued content that has expired from seekable history.
  Estimates do not establish render timing or wall-clock mapping.

History is validated at dispatch, not again at acknowledgement: expiry during an
accepted in-flight command does not rewrite its result. Replacement does fence
that result. Test application disposal and source replacement using the app's
own lifecycle as well as `replaceSubscription`; the fixture does not implement
application state machines. Host tests prove contract consumption, not device
rendering or real-server behavior.
