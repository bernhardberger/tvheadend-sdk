# Programme-time estimates

SDK 0.9.0 replaces the unavailable-only wall-clock enum with
`TimeshiftWallClockMapping.Estimate` / `Unavailable`. This is schedule-grade
alignment, not paired broadcast UTC or a precise or bounded-error guarantee.

## Consumer route

Sample `TvheadendPlaybackCoordinator.timeshiftPlaybackPosition`. For an
`Estimate`, use its `timeline.wallClockMapping`, when available, and call
`mapping.estimate(sample.target)`. The history and target in that sample belong
to the same subscription. When reading history separately, first check
`describesSameSubscription`. Missing history or an unavailable mapping means no
programme-time estimate, not device time.

For a preview retain that immutable timeline and mapping, select a content target
with `timeline.select(position)`, and estimate its schedule time with the retained
mapping. New status or EPG observations do not change that snapshot or target.
Pass the target to the existing content-seek API: its latest-history validation,
replacement rejection and actual media-coordinate seek remain authoritative.
The application owns EPG lookup, programme windows and labels. A snapshot is
historical evidence, not a promise that its subscription remains active.

`media3.testing.TimeshiftTestFixture.updateHistory(estimatedLiveEdgeTime = ...)`
provides the public offline route. Omit the argument for unavailable timing;
script replacement separately. No server, Player or Android runtime is required.
The fixture scripts availability directly; it does not reproduce production
edge-advance or discontinuity gating. Omit timing to script those unavailable states.

## Approximation limits

The existing connect-time server-time request supplies a connection-scoped
`kotlin.time.Instant` and `TimeSource.Monotonic` mark. Existing status observations
associate their observed buffer end with server time plus monotonic elapsed time.
Device wall-clock changes cannot move this clock. Servers lacking that existing
time observation produce unavailable estimates; no extra request is made.

Server timestamp resolution, network delay, queued status, upstream buffering,
server clock drift and inaccurate schedules all affect alignment. No measured
accuracy bound or performance latency claim is made. A stalled edge does not get
a fresh anchor. Missing timing/bounds produce unavailable state. A backward edge,
dropped stream data or a repeated subscription start conservatively disables new
estimates until subscription replacement. Reconnect and source replacement start
without inherited evidence. Reader pause alone does not invalidate the mapping;
advancing live-edge status may still refresh it. Seek rebasing does not change
content-to-wall-clock coordinates. Retained snapshots never extrapolate a live
edge or change history bounds.
On Android, the monotonic source may exclude deep sleep. A surviving connection
can therefore underestimate time after sleep until reconnect establishes a fresh
server-time observation. Advancing status alone does not correct that clock error.

## Resource cost

The implementation stores one server-time observation per gateway and one mapping
per attachment. Each existing status performs constant arithmetic and replaces at
most one mapping; it retains no anchor list. Packet handling does not create or
refresh wall-clock mappings. No timer, polling coroutine or new protocol request
is introduced. Focused tests exercise 100,000 production packet dispatches with the
same retained mapping identity and 100,000 clock reads with one observation;
these are bounded-state regression evidence, not throughput benchmarks.
