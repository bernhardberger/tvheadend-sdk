# Content Seek Settlement

SDK 0.12.0 repairs the production Media3 handoff for accepted content seeks.
It does not change pause intent, resume a Player, or send a server resume.

## Consumer Contract

`TimeshiftContentSeekResult.Completed` describes the command outcome, not a
completed playback or rendered frame. For `ACCEPTED`, `seek` is a non-null opaque
identity for that command, even when `readerReached` is null. A null reader
coordinate means unknown, not failure and not the requested position.

Retain the accepted `seek` identity. A subsequently sampled
`TimeshiftPlaybackPosition.Estimate` belongs to that seek only when
`estimate.seek === completed.seek` and the token is non-null. An old estimate,
an unavailable sample, or a sample with a different token is not settlement for
that command. Do not replace this fence with elapsed time or distance from the
requested target. New commands have distinct tokens, even at the same edge.
Tokens cannot be persisted or carried across segment replacement.

The matching estimate establishes playback-position settlement: the production
period consumed the ordered accepted skip, discarded the old queues, waited for
complete keyframe-admitted samples on selected A/V streams, returned their
rebased discontinuity to Media3, and the sampled Player position maps into the
new packet range. This is still a packet-PTS estimate, not exact content time,
renderer readiness, or a decoded/displayed-frame acknowledgement.

There are four distinct observations:

- Command acceptance: the ordered server acknowledgement accepted the command.
- Reader coordinate: optional `readerReached`, derived from that acknowledgement.
  It does not prove that post-command bytes have arrived at an elementary reader.
- Playback-position settlement: a matching seek token on a sampled estimate.
  Missing, incomplete, or delayed media can leave this unavailable indefinitely.
- Decoded paused frame: requires renderer/output evidence. The SDK does not expose
  a seek-correlated frame acknowledgement. Neither acceptance nor a matching
  estimate proves physical display, and no app or TV acceptance is claimed here.

At either history edge the SDK validates the selected coordinate against the
latest observed bounds; it does not clamp, extrapolate, or invent media at the
edge. No decodable post-command data means no new paused frame. A paused server
may withhold data; this implementation never overrides that pause to obtain it.

`TimeshiftTestFixture.completed()` supplies an acceptance token without moving
the displayed estimate. Script an old/unavailable estimate, then use
`fixture.playbackPosition(position, completed.seek)` for correlated position
evidence. This fixture does not simulate decoding. Test null `readerReached`,
delayed samples beyond presentation timeouts, repeated edge seeks, replacement,
and cancellation independently.

## Production Evidence

The owning regressions use `ScriptedSubscriptionConnection` below the real
subscription gate/rebaser and the production Media3 source, readers and queues.
Host tests cover queued/in-flight old packets, an empty timestamp anchor,
delayed complete samples, both edges, single-consumption discontinuity, token
ordering, rejected commands, and existing lifecycle/segment/cancellation fences.

The LXC119 Android TV emulator tests use real ExoPlayer, H.264/MPEG audio readers,
decoders and a Surface. They exercise playing and paused seeks at both scripted
edges; acknowledgement alone must not produce an internal discontinuity or a
new first-frame callback. Delayed usable media must produce both, a correlated
position sample, and an unchanged paused clock without a server-speed resume.
The recorded fixture's streams have independent timestamp origins; resumed
fixtures align their origins while preserving per-stream spacing and decode
offsets. These scripted timings do not characterize a live server.

Pinned Media3 1.11.0 `MediaPeriod.readDiscontinuity` resets output pipelines.
`ExoPlayerImplInternal.updatePlaybackPositions` calls `resetRendererPosition`
even for an equal-position discontinuity. `MediaCodecVideoRenderer.onPositionReset`
resets first-frame release eligibility, permitting a new first frame while paused.
`SampleQueue.reset(false)` discards old samples and requires a new keyframe while
retaining the format. This is why the owning repair is in the period, not an
optimistic `Player.seekTo` using server content coordinates.
