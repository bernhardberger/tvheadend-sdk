# HTSP stream restart contract

An HTSP `Stopped` interrupts one stream segment, not its subscription or connection.
The following validated `Started` may change codec, stream indices, and track count.
A repeated `Started` without a stop takes the same replacement route, including when
the layout is unchanged. The application-owned Player and its play intent are retained.

## Ownership and bounds

- The live source owns one subscription, ordered consumer, and terminal observer per
  source preparation. Periods own only segment-local readers, discovery, and sample queues.
  Releasing an old period cannot unsubscribe or detach its successor. Explicit source
  retirement closes the handle once, including a late successful open. Media3 re-preparing
  a retired source uses a new owner; old consumers, posts, and joins are fenced.
- Stop immediately fences old ingress, sample reads, preparation callbacks, mapping, and
  seek admission. It publishes neither EOS nor an empty timeline. A waiting replacement
  period prepares once after validated metadata and the existing format-discovery rules.
  Optional formats retain the one-second discovery allowance from SDK-05.
- The handoff holds at most 2,048 events and 16 MiB of packet payload. Overflow fails the
  subscription rather than blocking its consumer waiting for Media3. At most one timeline
  refresh and one worker drain are pending per source preparation; rapid epochs coalesce.
- Missing restart metadata has a five-second interruption deadline. Repeated stops do
  not extend it. Validated playability cancels it, and its owner token prevents a stale
  timer from terminating a later segment. This is a bounded policy, not a measured
  broadcast guarantee. Actual transport termination, generation loss, and close stay terminal.
  If initial open is still pending, the deadline's failure returns only after unsubscribe
  and ordered consumer drain complete, like other startup failures.
- Between stop and the next Started, Media3 drops nonterminal observations as well as
  packets. Interruption-era timeshift history, speed, and status cannot authorize the
  successor's timeline or mapping. Terminal delivery retains its existing route.
- Stop retires the period attachment immediately. Its stop-specific issue is intentionally
  not retained in the coordinator's live observation; that observation becomes unavailable
  until replacement evidence arrives. Direct infrastructure consumers still receive the
  ordered stop and its issue outside the uncertain-seek exception below. This boundary
  does not add a separate durable interruption-issue state.

## Media3 and timestamp handling

Maintained Media3 1.11.0 supplies timeline resolution, renderer teardown/selection,
elementary readers, subtitle conversion, and `SampleQueue` timestamp offsets. No new
parser, codec, or clock conversion was implemented.

The source publishes a `ForwardingTimeline` over `SinglePeriodTimeline`: fresh opaque
period UID, stable window UID and media item, unknown duration, fixed zero default and
period origin, and suppressed dynamic default projection. Refresh runs on the captured
playback looper, where `BaseMediaSource` synchronously notifies its callers. An initial
real timeline also avoids placeholder UID ambiguity. Player sampling unwraps the
playlist UID with Media3's `AbstractConcatenatedTimeline` utility.

Media3's `ExoPlayerImplInternal` resolves disappearing periods against the surviving
window, clears the old queue/renderers, prepares a replacement holder, and selects its
fresh groups. Already-published groups never mutate and `onPrepared` is not repeated.
Relevant 1.11.0 implementation regions are 1838–1917, 2644–2655, 2769–2786, 3443–3460,
3985–4047, and 4393–4408. `SinglePeriodTimeline`'s explicit constructor controls default
projection; `SampleQueue.setSampleOffsetUs` adjusts both media and subtitle sample timing.

The established subscription clock and delivered seek-rebase offset survive restart.
Only the track-dependent anchor candidates are replaced; an already accepted seek's
pending re-anchor and its safety floor remain valid. Each new period uses
one common origin for every queue, installed before sample commit. Original server PTS
remains separate for content mapping; A/V offsets are not normalized independently.
Before that origin is known, the period retains supported untimed packets and intervening
reader-discontinuity controls in order, bounded to 256 events and 4 MiB of payload.
The first usable presentation timestamp establishes the common offset on every queue
before the prefix is replayed through maintained Media3 readers. This preserves untimed
codec parameter sets without assigning a separate clock origin to each track. Overflow
uses the existing period/consumer failure route; interruption, release, failure, and
termination clear the retained prefix. Unsupported streams remain excluded before retention.
Ordinary seek retains old packet mappings; restart replaces them.
After restart, 1,024 consecutive timed packets rejected by an established seek floor
terminate the subscription with `RESUMED_SEGMENT_UNANCHORABLE`, rather than leave it
silently playable forever. Timed delivery above the floor replenishes the budget;
repeated stop/start metadata alone does not. This protection preserves the established
clock and offset instead of silently changing them to accept lower timestamps.

## Seek and consumer API

SDK 0.10.0 changes the provisional infrastructure API and adds segment validity
to the consumer contract. Infrastructure consumers must rebuild and update
exhaustive seek-result handling for `SegmentUnavailable`.

`SubscriptionState.Starting` also means an interrupted, currently nonplayable segment.
Open completes once; subsequent playability changes do not replace the transport handle.
Each validated `SubscriptionTracks` instance is the segment identity, even for identical
layouts. Its infrastructure constructor is public so SDK boundary fakes can model it.

The new `ActiveSubscription.seek(target, expectedTracks)` overload compares that identity
under the same lock that registers the seek gate. Stale or interrupted registration
returns `SegmentUnavailable` without dispatch. The Media3 bridge uses this overload;
content requests map stale segments to `Replaced`, and relative/live commands report
nonterminal `UNAVAILABLE`. An unresolved seek at stop/start is conservatively invalidated
with `UNCERTAIN_REQUEST_OUTCOME`, including a registered but not yet dispatched request.
Late acknowledgements cannot resurrect it. No fake protocol epoch correlation is assumed.
In this narrow uncertain-seek case the boundary itself and subsequent consumer events
are suppressed, not replayed into possibly mismatched readers. The terminal state flow
is authoritative for that invalidation. The general ordered-control delivery guarantee
does not promise consumer delivery after this fail-closed decision.

`TimeshiftTimeline.describesSameSubscription` retains transport-identity semantics.
`describesSameSegment` is the additional check for combining sampled content and history
across restarts. Opaque content targets are segment-scoped. Player position snapshots are
fenced by actual period UID before/after sampling and against the active attachment.
Missing period identities are unavailable, never a matching null/null pair. An unbound
attachment publishes no history timeline rather than substitute an attachment identity
for the eventual transport identity.
Equal numeric positions in different periods do not imply the same displayed content.
`TimeshiftTestFixture.restartSegment()` models that distinction without changing transport
identity. JVM ABI was regenerated with `:sdk-playback:updateLegacyAbi`; Android ABI remains
subject to the repository's maintained-tooling limitation.

## Evidence boundary

Host tests cover unchanged/changed layouts, stop before preparation and during discovery,
queued-media fencing, pending seek/late acknowledgement, retained targets and player UIDs,
handoff bounds, rapid epochs, missing restart, generation loss, late open/release, source
re-preparation, and continuing large-clock A/V timestamps mapped near zero.

`LiveStreamRestartInstrumentationTest` uses real ExoPlayer and maintained TVHeadend
renderers with recorded fixtures. It checks actual timeline replacement and selected
tracks for both playing and paused intent. Both tests passed on the configured Android
device on 2026-09-08 through `tools/sdk-device`, using unchanged MPEG-audio followed
by AC-3 and a one-to-two track replacement. They verify fresh timeline identity,
selection, near-zero position, preserved play/pause intent, and one subscription
until final close. The initial run exposed nonexistent fixture paths; the tests
now load the existing recorded Android assets before creating the subscription owner.
This is offline audio runtime evidence, not live-server video restart or a guarantee
of interruption-free playback across codecs. SDK 0.10.0 pins published HTSP 0.9.0
without sibling substitution. Publication remains separately verified external state.
