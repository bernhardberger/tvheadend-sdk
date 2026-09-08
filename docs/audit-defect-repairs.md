# Playback And EPG Defect Repairs

These repairs address SDK-01 through SDK-05 and SDK-10 from the 2026-09-08
static audit. They complement the [cache and metadata repairs](cache-metadata-repairs.md).
Host regressions establish the failure paths and corrected behavior, not their
frequency on broadcasts or physical playback acceptance.

## Seek And Terminal State

- SDK-01: a state-only subscription terminal now stops period loading and exposes
  the existing sanitized error to Media3. An explicitly delivered clean termination
  still produces EOS. Released periods and queued preparation callbacks remain fenced.
  Failed and ended periods ignore subsequent non-terminal mapping updates.
- SDK-02: transport cancellation while an owned seek is unresolved completes the
  caller exceptionally and terminates the uncertain subscription without replaying
  buffered packets. A previously ordered acknowledgement remains authoritative.
  Caller cancellation still leaves a claimed gate under subscription ownership;
  owner teardown retains its existing settlement behavior. When called through
  the playback coordinator, transport cancellation also retires that coordinator
  owner, as other operation cancellations do.
- SDK-03: content-seek cancellation reaches its caller as cancellation, just like
  relative seek and speed commands, before the coordinator owner retires.

`SubscriptionSeekGateTest`, `TvheadendPlaybackCoordinatorTest`, and
`TvheadendLiveMediaPeriodTest` cover these boundaries. The period regression uses
real media fixtures and the real subscription manager's unanchorable-segment path.

## Recording Replacement

SDK-04 restores the captured recording position through Media3's source-install
start-position overload. It does not reuse the original, potentially consumed
saved-progress resume request. Live-source restoration still uses its default
position. If restoration fails, retirement reports the pre-replacement snapshot
as an error, not the partially replaced player's position or apparent natural end.

`Media3PlaybackCoordinatorPlayerTest` covers live and recording replacements,
source-install and preparation failures, successful and failed restoration, a
consumed resume request, and subsequent pause and terminal progress reports.
The application continues to own the Player and its playback policy.

## Live Track Preparation

SDK-05 requires an initialized format for at least one stream in each advertised
supported audio/video category, rather than every alternative stream. Audio or
subtitle readiness therefore cannot bypass advertised video, and video cannot
bypass an entirely silent supported audio category.

After minimum category readiness, unresolved alternatives receive up to one second
for format discovery. Preparation commits immediately if all supported formats
arrive sooner; otherwise it commits at the deadline. This preserves normally
interleaved alternatives without letting a silent track block startup indefinitely.
The one-second allowance is a bounded policy, not a measured broadcast guarantee.

Available groups freeze when preparation is committed. An alternative still
unresolved at the deadline is omitted for that period; its later packets are
ignored before copying or allocation. Its partial queue is released only while
no reader is consuming, under the same lock used by playback-side output queries.
Unknown indices still fail, and unsupported codecs remain distinguishable from
omitted supported alternatives. Late track-group expansion and degradation of an
entire silent audio/video category are not implemented.

`TvheadendLiveMediaPeriodTest` uses maintained Media3 readers and recorded audio,
video, and subtitle fixtures to check preparation, stable groups, sample bytes,
allocation retirement, ignored late packets, and release/error callback fences.

## EPG Capacity

SDK-10 distinguishes applied queries, capacity rejection, and stale authority
internally. Capacity rejection settles waiting single requests as `Ineligible`
and batch channels as `Rejected`, without partially updating events or coverage.
It is not latched as a server capability denial. A later request can succeed when
capacity becomes available, subject to the existing cooldown. Observation expiry
and caller cancellation retain their existing precedence.

`EpgReducerTest` and `EpgWorkerTest` cover public repository acquisition, independent
batch channels, atomic rejection, cooldown retry, stale query authority, and
retirement before and after reduction. No public result types or ABI changed.

## Remaining Boundaries

SDK-06, SDK-07, SDK-11, performance qualification, and application findings remain
separate work. Stream restart integration has offline regression coverage described
in [the restart contract](stream-restart-contract.md), including offline real-Player device evidence.
No live server, performance measurement, publication, or consumer
release acceptance is claimed by these host regressions.

## HTSP Dependency

SDK 0.10.0 resolves the published HTSP 0.9.0 artifact, including its strict
reply-sequence validation and JSON diagnostic redaction. No unpublished sibling
checkout is substituted. HTSP 0.9.0 keeps subscription event collection open after
`Stopped` so it can deliver a later `Started` with replacement streams.

`Stopped` now interrupts the current segment without unsubscribing. A validated
`Started`, with or without a preceding stop, creates new Media3 readers and track
groups in a replacement period on the same transport and Player. An unresolved
seek intersecting this boundary still fails closed because the protocol skip
acknowledgement has no segment correlation. `SessionSubscriptionsTest` covers both
restart shapes and clearing stale near-live status. The source, period, seek-gate,
and coordinator host regressions cover ownership, bounds, and stale-coordinate
fences. Both real ExoPlayer playing/paused restart tests pass on the configured Android
device using recorded MPEG-audio and AC-3 fixtures; live-server video restart remains unverified.
