# Growing Matroska and MP4 Recording Evaluation

Date: 2026-11-11

Feasibility spike for Player issue #24: can a Tvheadend Matroska recording
that is still being written play through the SDK's Media3 path, at least up to
the point recorded so far? This note records the evidence behind the owner's
2026-11-11 update of [roadmap decision 12](roadmap.md) (see
[Recommendation for #24](#recommendation-for-24)) and measurements of the
SDK adapter that followed it
([Adapter over the real recordings](#adapter-over-the-real-recordings)).

## Answer

| Question | Verdict |
|---|---|
| Forward-only play from the start to the live edge, then on to completion, built-in Matroska | **Yes.** Stock `MatroskaExtractor` and `ProgressiveMediaSource` play it unchanged when the data source has unknown length and waits at the edge. |
| Seeking and resume in a growing Matroska file | **Yes, with a P7-style wrapper.** No parser fork and no custom `MediaPeriod` were needed. Not free: it needs a cluster index probe of its own. |
| libav Matroska (`avmatroska` profile) | **Layout yes, video no.** Clusters are append-only like the built-in muxer's, and the SDK's extent probe and cluster seeks work on them. But H.264 and HEVC samples are written in Annex-B form under an `avcC` codec header, so Media3 fails to parse them in finished and growing files alike. See [libav Matroska](#libav-matroska-avmatroska). |
| MP4 (Tvheadend's libav `avmp4` profile) | **Forward parsing yes, but not worth admitting for 1.0.** A test recording parses forward to its end like Matroska. But the server's bundled FFmpeg cannot record AC-3 services as MP4 at all, and Media3 reads a 49.7-day duration from the files it does write, so even a finished MP4 recording neither ends nor seeks correctly. See [MP4 test recording](#mp4-test-recording). |

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
- `avmp4` lists MPEG-2/H.264/HEVC video and MP2, AC-3, E-AC-3, AAC and AC-4
  audio (`muxer_libav.c:290-300`). DVB subtitles and teletext are dropped.
- In practice AC-3 and E-AC-3 fail. `lav_muxer_add_stream` takes each stream's
  codec id from `avcodec_find_encoder`, and the bundled FFmpeg is built with
  only the `mpeg2video mp2 aac vorbis flac` encoders (`Makefile.ffmpeg:40`). An
  AC-3 stream gets codec `none`, the MP4 header write fails, and the recording
  ends as "File not created". This was reproduced on 3sat HD. Most German and
  Austrian HD services on the test server carry AC-3.

### libav Matroska (`avmatroska`)

- `lav_muxer_init` opens FFmpeg's `matroska` muxer on a seekable file. FFmpeg
  6.1.1 `matroskaenc.c` buffers each cluster and writes it whole
  (`:2861-2871`, `:2984-2996`); at close it appends Cues and fills in the
  SeekHead, Duration and Segment size. Each cluster starts with a CRC-32
  element before its Timecode.
- The encoder limit from MP4 applies here too: no AC-3 or E-AC-3. DVB subtitles
  fail as well ("Subtitle codec 0 is not supported"), so an arte HD attempt
  ended as "File not created". A 12-minute recording of an SD service with H.264
  and MP2 succeeded (168 MB, 697 clusters). Copies taken during recording
  differ from the finished file only in the header bytes before the first
  cluster.
- **Media3 cannot read the H.264 and HEVC samples.** Tvheadend gives FFmpeg an
  `avcC` codec header (`muxer_libav.c:131`) but converts samples from Annex-B
  start codes to length prefixes only for MP4 (`:643-646`). FFmpeg converts
  Matroska samples only when the codec header is itself Annex-B
  (`matroskaenc.c:3405-3408`). The file therefore declares length-prefixed
  samples but carries start codes, in 17,948 of the 17,997 video blocks of the
  test recording. Stock `MatroskaExtractor` fails on the finished file with
  `IllegalStateException: Top bit not zero`. MPEG-2 video is not converted and
  is not affected. This is a Tvheadend defect; the SDK does not work around it.

### Other containers Tvheadend can record

`muxer_create` (`muxer.c:321-345`) tries the pass-through, built-in Matroska,
audio-ES and libav muxers in turn. The file suffixes come from `muxer.c:107-140`:

| Container | Suffix | How a recording gets it |
|---|---|---|
| MPEG-TS pass-through | `.ts` | Default `pass` profile. Already growing-capable. |
| Built-in Matroska | `.mkv`, audio-only `.mka` | `matroska` profile. In 1.0 scope. |
| Built-in WebM | `.webm` | Same muxer with the WebM flag (`muxer_mkv.c:1610-1624`). Needs a transcode profile that produces WebM codecs. |
| libav Matroska | **`.mkv`, the same suffix as built-in** | `avmatroska` profile, FFmpeg's muxer. H.264/HEVC unreadable, see above. |
| libav MP4 | `.mp4` | `avmp4` profile, tested below. |
| MPEG-PS, audio ES (`.mpeg`, `.mp2`, `.ac3`, `.aac`, `.oga`, `.ac4`) | various | Transcode and audio-only profiles (`profile.c:2731-2741`); not examined further. |

Two consequences for admission. Built-in WebM and `.mka` come from the same
Matroska muxer, so the same file layout follows, but neither was recorded here
and neither is admitted. And admission by suffix cannot tell built-in from
libav Matroska: both write `.mkv`. The owner chose to admit every `.mkv` file,
so a growing libav H.264 recording fails with the same parser error as a
finished one.

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

### MP4 test recording

A second, non-default DVR configuration with an `avmp4` stream profile recorded
12 minutes of arte HD (H.264 video and four MP2 tracks; DVB subtitles and
teletext were dropped), because the 3sat attempt failed on AC-3 (see
[libav MP4](#libav-mp4-avmp4)). The same harness, with Media3's
`FragmentedMp4Extractor`, ran over a 282 MB and a 320 MB copy taken during
recording and over the finished 654 MB file.

The file has the layout the source predicts. `moov` and a first `mdat` sit at
the start, followed by 35,995 `moof`/`mdat` pairs (18 KB each on average, one
video frame per fragment) and a 3 MB `mfra` written at close. Each copy is a
byte-exact prefix of the finished file.

| Case | Outcome |
|---|---|
| Extractor, copy ending inside an `mdat`, known or unknown length | Definitive `SeekMap.Unseekable` at the first fragment; `EOFException` at the copy's end after emitting the samples it had. |
| Player, copy, known length | READY, not seekable, `seekTo(30 s)` stays at 0; plays to 326 s, then a source error (`EOFException`). |
| **Extractor, unknown length, data source waits at the edge**; the file grows in unaligned 7 MB steps to the finished file | Ends cleanly after 54 edge waits. Sample counts match a known-length read of the finished file exactly (35,996 video samples with 1,168 keyframes, about 29,970 per MP2 track). |
| Player over the same growing source | READY, not reported live, then plays past the media's end: still READY at 100,719 s when the harness gave up. It never reaches ENDED. |
| Finished file through the SDK's finished-recording extractor factory | Seekable through the `mfra` index, but with the same bogus duration. `seekTo(600 s)` fails with `ERROR_CODE_PARSING_CONTAINER_MALFORMED` ("Offset to end of mdat was negative"). |

Two defects in the written file cause the player results; neither is specific
to growth:

- **Duration.** The video `tkhd` duration is `0xFFFFFFB0` (−80 ms written as an
  unsigned 32-bit value; its `mdhd` is likewise negative). Media3 takes it as
  4,294,967,216 ms, about 49.7 days, as the item duration. ExoPlayer only
  finishes once the position reaches a known duration
  (`ExoPlayerImplInternal.java:1515-1519`), so playback idles after the last
  sample instead of ending.
- **Seek index.** The video `tfra` lists all 35,995 fragments, not just the
  1,168 that hold keyframes, and their times follow presentation order: 17,995
  of them are earlier than the entry before.

So stock Media3 parses a growing Tvheadend MP4 forward exactly like Matroska,
but a usable MP4 path needs its own duration override and its own keyframe
index, even for finished recordings.

### Adapter over the real recordings

The SDK's Matroska adapter probes the extent from head and tail windows and
seeks with Media3's `BinarySearchSeeker`, landing only on validated Cluster
starts. A temporary harness ran it over the 3sat built-in recording, with the
header patched back to its growing shape, and over the libav recording. Both
copies were deleted afterwards.

| Case | Outcome |
|---|---|
| Extent probe at 20, 50 and 100 % of the file and after completion | Correct first-cluster time (0 built-in, 280 ms libav), duration and finality for both. While growing, about 36 KB read per probe sample. |
| Seeks to 0, 10, 50, 90 and 99 % on a half and a finished extent | Always a Cluster start at or before the target, 0 to 1.43 s early. Each seek read 0.02 to 8.3 MB (built-in) or 0.13 to 1.1 MB (libav), about two to five clusters. |
| Growing extraction of the built-in file in unaligned 7 MB steps, seek at 50 % | For a 119.66 s target the first video sample is the keyframe at 118.37 s. While growing, the map is estimated and seekable with a duration (359 s at half); at completion it is final (719.45 s). |
| Growing extraction of the libav file | Not possible because of the Annex-B samples above. |

The first version of the seeker read a fixed 32 MiB window per search step
(33 to 134 MB per seek) and sometimes landed 3.5 to 16 s early. Incremental
16 KiB reads and a strict floor fixed both before these numbers were taken.

## Carry-over cost and risk

The SDK already has the generation-bound growth lease, the unknown-length
blocking data source, finality handling, and the coordinator path for TS.
Estimates below are rough and assume that infrastructure.

| Package | Scope | Rough estimate |
|---|---|---|
| Forward-only Matroska | Admit `.mkv` growing paths (`sdk-core` `PlaybackBinding.kt:340`, the coordinator's deferred admission), select `MatroskaExtractor` instead of the TS wrapper, make the data source's 188-byte delivery alignment container-neutral, and state the live, duration-less, unseekable presentation in the consumer contract. Add a synthetic (non-broadcast) Matroska fixture, a review, and device acceptance. | 4-7 engineer-days |
| Seek and resume, Matroska | Wrapper as above; an extent probe that walks cluster headers and republishes keyframe-start indexes as the file grows; edge clamping; handover at completion. | +6-10 engineer-days |
| MP4 | Duration override and a keyframe-only index of its own (a `moof`/`tfdt` walker over one-frame fragments), which finished MP4 recordings need as well. Only services without AC-3 can be recorded at all with the bundled FFmpeg. | Not estimated; out of 1.0 |

Risks and upgrade exposure:

- **Payload lookalikes.** Bounded Cluster searches check declared track numbers,
  timestamp bounds, element sizes, and following Segment boundaries. They do not
  walk every element from the Segment start. Crafted block payloads can still
  imitate valid headers and cause a parse error after seeking; this is not
  memory unsafety. Tail sampling reads at most a 16 MiB window, plus small
  boundary checks. A seek stops after 64 timestamp searches at its validated
  floor Cluster if it has not converged.

- **The same Media3 ordering rule as P7.** `ProgressiveMediaSource` ignores
  estimated maps after any definitive one, so the wrapper must keep
  suppressing the inner `Unseekable`. A Media3 change there breaks TS and
  Matroska seeking together.
- **Resuming at a cluster without Cues.** It works because `MatroskaExtractor`
  resumes at any cluster position after `seek()`, which is also how it seeks
  with Cues. That is stable, public behavior.
- **No maintained element-header walker.** Media3's EBML reader and varint
  reader are package-private, and running a second full extractor would read
  the whole recording (about 1.7 MB/s here). The adapter reaches Media3's
  `VarintReader` through a small bridge class in Media3's package and owns only
  the Cluster-header check. A Media3 upgrade that changes that package-private
  class breaks the build, not playback.
- **Coarse seek points.** Cluster starts are up to 6 s apart, and clusters can
  reach 10 MB. Seeking stays exact through decode-forward, but a seek near the
  edge can wait for the next cluster.
- **Stalls.** The harness data source waited forever, so a stalled source in
  the harness ends only at Media3's 10-minute stuck-buffering limit. The SDK's
  own growing reader is stricter for every container: a read fails with
  `TIMEOUT` after 30 seconds without new bytes
  (`sdk-core` `GrowingRecordingFileReader.kt:543,577,673`).
- **Admission by suffix.** Built-in and libav Matroska both write `.mkv`, so a
  suffix check also admits `avmatroska` recordings, whose H.264 and HEVC video
  fails to parse.
- **Not covered:** H.265 and MPEG-2 video in Matroska, other channels and
  bitrates, WebM, reconnect, and device playback.

## Recommendation for #24

Forward-only play-to-edge of the built-in Matroska profile is the low-risk
part. It reuses the existing growth source and stock Media3 parsing, and it can
be scoped on its own. Seeking and resume are feasible on the P7 pattern and
cost roughly as much again.

Owner decision, 2026-11-11: built-in Matroska with seeking and resume is in
scope for 1.0, and [roadmap decision 12](roadmap.md) now says so. MP4 stays out
until it has its own test recording. The implementation has no release slot
yet and needs an independent review before it lands.

The MP4 test recording, made the same evening, does not change that. Growing
MP4 parses forward like Matroska, but on the bundled FFmpeg build it cannot
record AC-3 services, and the files it does write carry a bogus duration and a
seek index that is not keyframe-only. Those defects affect finished MP4
recordings as well, so they belong to a separate finished-MP4 fix before any
growing MP4 work.

The libav Matroska recording, made the same evening, settled admission. The
owner chose to admit every `.mkv` recording, not only built-in ones. libav
Matroska files share the built-in layout, so the SDK path applies to them, but
their H.264 and HEVC samples need a Tvheadend fix before Media3 can read them,
finished or growing.

Two points from the spike belong in the Matroska implementation's scope.
First, the estimated seek map with a duration also stops Media3 from reporting
the recording as live, so forward playback before the first index is published
should already use an estimated map with a duration. Second, stalled growth is
already bounded by the SDK's growing reader (30 seconds without new bytes
fails the read with `TIMEOUT`). The implementation should keep that bound for
Matroska and test it, not add a second one.
