# Timeshift Content Contract Evidence

This is local source and host-test evidence for P24-S1 / P24-A2 handoff, not
device, capture, or server-operation verification. SDK owns coordinate semantics;
Player owns presentation, focus, capacity display policy, and observation cadence.
No Player changes or cross-repository dependency substitution are part of this
packet. The normative app contract is in `consumer-guide.md`, Timeshift.

## Source Evidence

Read-only TVHeadend source inspection found the existing absolute primitive in
`src/htsp_server.c:2824-2829`. `src/timeshift/timeshift_reader.c:478-505`
reports the observed seekable endpoints and reader shift; lines 815-831 emit
the absolute reached reader coordinate, not displayed content.
`src/htsp_server.c:4593-4609` sends status followed by the skip acknowledgement.

`src/timeshift.c:310-326` uses the packet PTS high-water mark in elementary-packet
mode. Lines 352-380 distinguish packet timestamp adjustment from raw MPEG-TS
monotonic timing. The SDK Media3 live path consumes elementary mux packets, not
that raw MPEG-TS clock. The same packet-mode relationship was confirmed in the
local upstream `v4.2.8:src/timeshift.c`; no new server primitive or raised minimum
server version is required. `src/htsp_server.c:4192-4202` emits packet PTS/DTS in
the negotiated unit base. These observations establish stream coordinates, not
UTC. Neither packet PTS nor status supplies a programme wall-clock anchor.

The SDK retains original server PTS on `SubscriptionEvent.Packet` when its
existing timestamp rebaser shifts output PTS/DTS. Media3's live single-period
timeline has no window offset; its elementary reader receives the output PTS.
The mapping uses sampled player position and retained output-to-server segments,
not the latest status reader or queued packet position. It remains an estimate
because packet timing is not an observation of a rendered frame. Lower-layer
seek gating, keyframe anchoring, packet queue policy and command order remain
unchanged.

## Focused Regressions

- `TimeshiftTimelineTest`: stable target through edge movement; expiry without
  clamping; replacement fencing before and during seek; missing history and
  missing reached-time uncertainty; queued segment preservation across rebases.
- `TvheadendPlaybackCoordinatorTest`: sampled player position across pause,
  server-reader advance, seek acknowledgement, queued old content, rebasing and
  period replacement; existing lifecycle and command ordering regressions.
- `SubscriptionSeekGateTest`: request-correlated absolute reader outcome is
  available even when acknowledgement consumer delivery is suspended.
- `TvheadendLiveMediaSourceTest`: actual subscription-to-period packet delivery
  preserves pre-seek and rebased audio mapping without requiring a rendered frame.
- `SubscriptionTimestampRebaseTest`: original server PTS survives shared
  audio/video output rebasing; existing keyframe and invalidation regressions.

Host gates, independent review and delivery evidence are recorded with the package
result. P24-A2 must use validated published `0.6.0` coordinates, not a dirty
checkout or an unapproved dependency substitution.
