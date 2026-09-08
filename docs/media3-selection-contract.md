# Media3 Selection Contract

Each live period advertises one single-format `TrackGroup` per initialized output.
Selection accepts a current group or an equal, non-identical group, with exactly
one selected track at group index zero. Unavailable groups and invalid indices
are rejected. Equality includes the group ID and complete format; it is not a
language-only or codec-only fallback across changed tracks.

Media3 1.11.0 `DefaultTrackSelector` matches `TrackSelectionOverride` by group
equality, then constructs its selection definition with the stored override's
group. An override from an earlier period can therefore legitimately reach the
new period with an equal group that is not the same object. Media3's own
`ProgressiveMediaPeriod` uses `TrackGroupArray.indexOf` (equality) and validates
the same single-track boundary. SDK 0.10.0 instead required object identity.

SDK 0.10.1 resolves that equal group to the current output, never to the earlier
period's queue. A retention flag permits reuse only when the supplied stream
already wraps that current queue. Switching queues creates and seeks a new stream
and sets the reset flag. Null selection removes the stream. Existing interruption,
release, cancellation propagation, and segment/restart fences remain unchanged.

## Evidence

- `TvheadendLiveMediaPeriodTest`: 16 focused JVM cases pass, including two new
  regressions that failed against the original implementation. They exercise real
  readers/queues through the existing scripted subscription boundary, without
  reflection or a new test framework.
- `LiveStreamRestartInstrumentationTest`: both playing and paused cases pass on
  the identity-verified offline LXC119 Android TV emulator. The real ExoPlayer
  retains an explicit first-period override across an equal fresh restart group,
  falls back for changed AC-3 groups, and reuses the override on a new-channel
  MPEG-audio retune. Track selection, READY state, play/pause intent, position,
  and subscribe/unsubscribe counts are asserted.
- Restoring only the old identity lookup makes the paused real-Player case fail
  with `Restart must not produce a Player error` (one test, one failure). The
  repaired lookup passes both cases. This is a selector-path negative control,
  separate from the original Player direct-period probe.

Recorded packets and muted emulator playback do not prove the consumer app's
channel route, session preference policy, live-server behavior, or physical audio.
Those remain consumer-owned acceptance. Consumers should update SDK coordinates
together to 0.10.1; no source migration from 0.10.0 is required. HTSP and native
provenance remain unchanged from 0.10.0.
