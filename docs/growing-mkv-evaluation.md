# Growing Matroska and MP4 Recording Evaluation

Date: 2026-11-11

Feasibility spike for Player issue #24: can a Tvheadend Matroska recording
that is still being written play through the SDK's Media3 path, at least up to
the point recorded so far? This note records the evidence behind the owner's
2026-11-11 update of [roadmap decision 12](roadmap.md) (see
[Recommendation for #24](#recommendation-for-24)). It schedules no
implementation.

## Answer

| Question | Verdict |
|---|---|
| Forward-only play from the start to the live edge, then on to completion, built-in Matroska | **Yes.** Stock `MatroskaExtractor` and `ProgressiveMediaSource` play it unchanged when the data source has unknown length and waits at the edge. |
| Seeking and resume in a growing Matroska file | **Yes, with a P7-style wrapper.** No parser fork and no custom `MediaPeriod` were needed. Not free: it needs a cluster index probe of its own. |
| MP4 (Tvheadend's libav `avmp4` profile) | **Probably for forward-only, not tested.** The file is fragmented and append-only, and Media3's fragmented extractor has the same unseekable-then-continue shape. No test recording was made. |

## What Tvheadend writes

The server under test runs Tvheadend 4.3-2894~gcf30f350 with local patch
branches. Its `src/muxer/muxer_mkv.c`, `ebml.c` and `muxer_libav.c` are
identical to upstream `2a60682`, which is where the line references below
point.

### Built-in Matroska (`matroska` stream profile)

- `mkv_muxer_init` (`muxer_mkv.c:1385-1419`) writes the EBML header and a
  Segment with the 8-byte **unknown size** `0x01FFFFFFFFFFFFFF`
  (`mk_build_segment_header`, `:583-605`). For a file it then writes a 500-byte
  Void reserved for the SeekHead, a SegmentInfo with a Void where Duration will
  go (`:225-247`), Tracks, and Tags.
- Clusters are built in memory and written whole when closed
  (`mk_close_cluster`, `:1061-1067`). A cluster closes at the next keyframe once
  it is larger than 2 MB or older than one second (`mk_write_frame_i`,
  `:1074-1166`). One Cue per cluster is kept in memory.
- Nothing is rewritten while recording. At close, `mk_mux_close`
  (`:1310-1347`) writes the last cluster, appends Cues and then Chapters after
  the last cluster, and only then rewrites the SeekHead into the reserved Void,
  writes Duration into SegmentInfo, and patches the Segment size.
- A Matroska recording cannot be split into a new segment
  (`mkv_muxer_reconfigure`, `:1444-1451`, returns an error).

The test recording confirms this byte for byte. A copy taken during recording
and the finished file differ only in the SeekHead and Duration bytes in the
first 800 bytes; every cluster is identical and append-only. The finished
12-minute file has 516 clusters, 515 of which start with a video keyframe, with
cluster starts 0.55 to 6.2 seconds apart (1.4 s average) and cluster sizes up
to 10 MB. The Cues (9 KB) sit after the last cluster.

So the Player roadmap's assumption holds: there is no seek index until the
recording closes.

### libav MP4 (`avmp4`)

- `lav_muxer_init` (`muxer_libav.c:449-451`) opens FFmpeg's `mp4` muxer with
  `frag_duration=1` and `ism_lookahead=0`. The server binary links FFmpeg
  statically (libavformat 60.16.100, FFmpeg 6.1, as in `Makefile.ffmpeg`).
- In FFmpeg 6.1.1 `movenc.c`, any `frag_duration` turns on fragmentation
  (`:6995-7002`). A one-microsecond limit flushes a fragment on almost every
  packet (`:6495-6500`). The first flush writes `moov` (with `mvex`) and the
  first `mdat` once every track has data (`:5898-5931`). After that the file is
  a sequence of `moof`/`mdat` pairs.
- At close, `av_write_trailer` appends `mfra` (`:7730-7736`); `moov` is not
  rewritten. The file is append-only from the first fragment on.
- `avmp4` carries MPEG-2/H.264/HEVC video and MP2, AC-3, E-AC-3, AAC and AC-4
  audio (`muxer_libav.c:290-300`). DVB subtitles and teletext are dropped.

## Media3 1.11.1 evidence

A temporary Robolectric harness drove the real `MatroskaExtractor`,
`ProgressiveMediaSource` and `ExoPlayer` (`TestExoPlayerBuilder`) over copies
of the test recording: a 101 MB and a 193 MB copy taken while it recorded, and
the finished 1.2 GB file. The recording was a 3sat HD broadcast with H.264
video, three MP2 tracks, AC-3, and DVB subtitles. The harness and the copies are
not committed, because they are broadcast content and single-decision evidence.

### What the stock path does

| Case | Outcome |
|---|---|
| Extractor, partial copy ending on a cluster boundary, known or unknown length | Parses normally and ends at the copy's end. It reports a definitive `SeekMap.Unseekable` with no duration at the first Cluster, because there are no Cues yet. |
| Extractor, copy cut mid-cluster or inside a cluster header, returning EOF there | `EOFException` after emitting the samples it had. |
| Player, partial copy, known length | READY, not seekable, no duration. `seekTo(30 s)` restarts at 0. Play ends with **STATE_ENDED** at the copy's end (61.5 s), a false end. |
| Player, cut mid-cluster, known length | Source error `ERROR_CODE_IO_UNSPECIFIED` (EOFException). |
| Player, cut mid-cluster, unknown length, EOF at the cut | Reported as live; buffers at the cut, then fails with `ERROR_CODE_TIMEOUT` (`StuckPlayerException`) after Media3's 10-minute stuck-buffering limit. |
| **Player, unknown length, data source waits at the edge**; the file grows from the 101 MB copy in unaligned 7 MB steps up to the finished file, including its Cues/Chapters tail and the stale header | READY at once, plays through 160 edge waits, many of them mid-cluster, and reaches **STATE_ENDED at 719.7 s with no error**. |
| Extractor over the same growing source | Ends cleanly. Sample counts per track match a known-length read of the finished file exactly (35,958 video samples with 1,083 keyframes, 29,965 per MP2 track, 22,470 AC-3). |
| Extractor, finished file, known length | Seeks to the Cues and back, publishes a seekable `MatroskaSeekMap` with 719.71 s duration; same sample counts. |

So a growing Matroska file needs exactly what the SDK's growing TS source
already does: report unknown length and wait at the edge instead of returning
EOF. The tail written at close (Cues and Chapters after the clusters) is
harmless to a reader that parsed the header before close; the extractor
ignores the Cues because it has already sent its map.

Two consumer-visible facts follow. During forward-only play the item is
reported live, with no duration and no seeking, until it ends. And because a
whole cluster lands at once, the playable edge trails the recorder by up to one
cluster span (6.2 s at most in this recording).

### Seeking through a P7-style wrapper

The harness composed the maintained `MatroskaExtractor` in a thin `Extractor`
wrapper, using only public `Extractor` and `SeekMap` API. Like the TS wrapper in
the [growing-recording evaluation](growing-recording-evaluation.md#p7-f1-feasibility-result),
it hides the inner definitive `Unseekable`, publishes an estimated unseekable
placeholder, then publishes an estimated `IndexSeekMap` over cluster start
positions and timecodes. A header-only walker built that index without parsing
blocks; it read 32 bytes per cluster. On the 193 MB copy it found 80
clusters, and its index was an exact prefix of the finished file's 516.

- Extractor seeks to the cluster at or before 45 s and 100 s resumed with a
  video keyframe at the cluster time and audio about 0.6 s earlier.
- A seek to 10 s hit a cluster that starts with a non-keyframe (6.77 s). That
  is one cluster in 80 here; a production index should list only clusters that
  start with a keyframe.
- In the player, with the wrapper, the 193 MB copy was READY, seekable, not
  live, and 115.4 s long. `seekTo(45 s)`, `seekTo(10 s)` and `seekTo(100 s)`
  each reached READY at exactly the requested position, and playback continued
  to 108 s without error.

Seeking was proven over a fixed copy with one index. Republishing the index as
the file grows was not run for Matroska. It is the same Media3 path the P7 TS
wrapper already uses.

## Carry-over cost and risk

The SDK already has the generation-bound growth lease, the unknown-length
blocking data source, finality handling, and the coordinator path for TS.
Estimates below are rough and assume that infrastructure.

| Package | Scope | Rough estimate |
|---|---|---|
| Forward-only Matroska | Admit `.mkv` growing paths (`sdk-core` `PlaybackBinding.kt:340`, the coordinator's deferred admission), select `MatroskaExtractor` instead of the TS wrapper, make the data source's 188-byte delivery alignment container-neutral, and state the live, duration-less, unseekable presentation in the consumer contract. Add a synthetic (non-broadcast) Matroska fixture, a review, and device acceptance. | 4-7 engineer-days |
| Seek and resume, Matroska | Wrapper as above; an extent probe that walks cluster headers and republishes keyframe-start indexes as the file grows; edge clamping; handover at completion. | +6-10 engineer-days |
| MP4 | Separate test recording first. Forward-only likely matches Matroska; seeking needs a `moof`/`tfdt` walker over very dense fragments. | Not estimated |

Risks and upgrade exposure:

- **The same Media3 ordering rule as P7.** `ProgressiveMediaSource` ignores
  estimated maps after any definitive one, so the wrapper must keep
  suppressing the inner `Unseekable`. A Media3 change there breaks TS and
  Matroska seeking together.
- **Resuming at a cluster without Cues.** It works because `MatroskaExtractor`
  resumes at any cluster position after `seek()`, which is also how it seeks
  with Cues. That is stable, public behavior.
- **No maintained element-header walker.** Media3's EBML reader and varint
  reader are package-private, and running a second full extractor would read
  the whole recording (about 1.7 MB/s here). The cluster walker would be a small
  bespoke parser and needs the justification AGENTS.md asks for.
- **Coarse seek points.** Cluster starts are up to 6 s apart, and clusters can
  reach 10 MB. Seeking stays exact through decode-forward, but a seek near the
  edge can wait for the next cluster.
- **Stalls.** A recording that stalls longer than Media3's 10-minute
  stuck-buffering limit ends forward-only playback with a timeout error.
- **Not covered by this spike:** H.265, other channels and bitrates, Tvheadend's
  libav Matroska profile (`avmatroska`, a different muxer), reconnect, and
  device playback.

## Recommendation for #24

Forward-only play-to-edge of the built-in Matroska profile is the low-risk
part. It reuses the existing growth source and stock Media3 parsing, and it can
be scoped on its own. Seeking and resume are feasible on the P7 pattern and
cost roughly as much again. MP4 should wait for its own test recording.

Owner decision, 2026-11-11: built-in Matroska with seeking and resume is in
scope for 1.0, and [roadmap decision 12](roadmap.md) now says so. MP4 stays out
until it has its own test recording. The implementation has no release slot
yet and needs an independent review before it lands.

Two points from the spike belong in that implementation's scope. First, the
estimated seek map with a duration also stops Media3 from reporting the
recording as live, so forward playback before the first index is published
should already use an estimated map with a duration. Second, the SDK should
detect stalled growth itself, while the DVR entry is still recording, and
report it well before Media3's 10-minute stuck-buffering limit.
