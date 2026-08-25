# P7-1 and P7-F1 Growing Recording Evaluation

Date: 2026-08-25

## Decision

**GO candidate for one separately approved pass-through MPEG-TS path; P7-2
remains blocked on the explicit operator decision.**

P7-F1 proved that exact Media3 1.11.0 can provide an approximate nonzero seek
over already parsed bytes without a parser fork or custom `MediaPeriod`. A thin
`Extractor` wrapper can delegate all container and elementary-stream parsing to
the maintained `TsExtractor`, replace its permanent unseekable map with
estimated updates, and let the stock `ProgressiveMediaPeriod` reopen a growing
data source at a packet-aligned byte position. Extraction continued from a
valid keyframe and continued again after later bytes were appended.

This result narrows, rather than removes, the P7-1 no-go. It proves one authored
MPEG-2/MP2 pass-through transport stream. It does not
prove H.264, arbitrary TS programme layouts or bitrates, built-in Matroska,
av-lib MP4, exact seek, active-server finality, reconnect, rollover, or product
integration. The stock `TsExtractor` remains unseekable without the wrapper.
P7-2 and P7-3 therefore remain behind the explicit operator decision gate.

## P7-F1 Feasibility Result

The executable proof is
`sdk-media3/src/androidTest/kotlin/at/bernhardberger/tvheadend/sdk/media3/GrowingTsSeekFeasibilityInstrumentationTest.kt`.
It uses `ProgressiveMediaSource` and therefore the real Media3
`ProgressiveMediaPeriod`; it does not instantiate a test substitute for the
period.

The proof composes `TsExtractor(SubtitleParser.Factory.UNSUPPORTED)` and
forwards its tracks and parser lifecycle. The wrapper observes Media3's own
video keyframe sample metadata and extractor input positions, associates each
timestamp with a 188-byte-aligned position 512 TS packets earlier, limits every
point to bytes already exposed by the data source, and publishes updated
estimated maps. The fixed pre-roll is an evidence parameter for this fixture,
not a universal production guarantee.

One Media3 ordering rule is load-bearing. `ProgressiveMediaSource` ignores an
estimated map after it has seen any non-estimated map. The wrapper must therefore
suppress `TsExtractor`'s definitive `SeekMap.Unseekable`, first publish an
estimated unseekable placeholder so preparation can finish, and keep every
later dynamic map estimated. The first G10 run exposed this rule when the
wrapper forwarded a non-estimated placeholder; the corrected proof verified
that later maps reach the player timeline.

The deterministic append source exposes half the fixture, reports unknown
length, and blocks rather than returning temporary EOF. The player then consumes
past an indexed point with zero back buffer, seeks backward, and forces the
normal progressive reopen and `Extractor.seek(position, timeUs)` cycle. Only
after that cycle succeeds does the source expose the second half.

The passing G10 run recorded these run observations, not fixture constants:

| Evidence | Observed value |
|---|---:|
| Fixture bytes initially available | 1,861,388 |
| Fixture bytes after append | 3,722,776 |
| Dynamic map publications | 5 |
| Requested timestamp | 5,920,000 us |
| Reopened packet-aligned byte | 834,344 |
| First post-seek keyframe error | 0 us |
| Maintained Media3 video reader | `video/mpeg2` |

The test also verified that no nonzero reopen or extractor seek preceded the
player request, the reopened byte equals the selected map point and is within
the proven initial range, `Extractor.seek` retains the requested timeline
timestamp within 1 ms, and the first keyframe does not skip past the target.
Appended bytes are then read, a later keyframe enlarges the map, and neither
temporary EOF nor later growth produces `Player.STATE_ENDED` or a player error.

The device was a TCL Smart TV Pro running Android 12, build
`STT2.230203.001 release-keys`; the runtime property
`ro.build.version.incremental` reported `AS50`.

## Scope And Sources

P7-1 was the evidence-only evaluation recorded for the maintainers' Phase 7
decision gate. P7-F1 adds only an Android instrumentation prototype, its
authored synthetic transport-stream fixture, and this evidence amendment. It
makes no production source, public API, build, dependency, or ABI change.

SDK source paths below are relative to the repository root. HTSP and pinned
TVHeadend paths refer to maintainer-local sibling checkouts used for
exact-version evidence; those checkouts are not shipped with this repository.

Evidence was taken from:

- the production SDK snapshot evaluated by P7-1 at commit
  `e16680eb8fcb88aa047f3bfb6534500862b606ce`;
- the P7-F1 test prototype based on committed P7-1 result
  `95ba0368c499f9425ebd29b848aaad9af68995a0`;
- `tvheadend-htsp` 0.7.0 source and tests;
- pinned TVHeadend source revision
  `27295c5a48f2c575678bb224014cb9a26a773083`;
- official AndroidX Media3 1.11.0 source at commit
  `2bc207851df311340767e913931ca7b28cab1794`; and
- a deterministic packet-aligned MPEG-2/MP2 fixture generated from FFmpeg 7.1.5
  `testsrc2` and `sine` filters. The fixture contains no broadcast or server
  data. `sdk-media3/src/androidTest/assets/p7-f1/README.md` records the exact
  generation command, 3,722,776-byte size, and SHA-256
  `ac5450c47d40b34277e3c304392f2476273717c9fcf8c91b78252702052a2447`.

## Findings

### HTSP can observe growth

The required typed wire operation already exists. No HTSP remediation or
release is indicated by the evaluated evidence.

- `../tvheadend-htsp/src/main/kotlin/at/bernhardberger/tvheadend/htsp/requests/HtspFileRequests.kt:19-23,78-87,154-166`
  exposes typed `FileStatResponse` and `HtspConnection.fileStat` for protocol
  version 8 or later.
- `../tvheadend-htsp/src/test/kotlin/at/bernhardberger/tvheadend/htsp/HtspProtocolCoreTest.kt:475-539`
  covers finite and omitted stat fields, malformed replies, unsupported
  protocol, and stale-generation cancellation.
- The exact default-resolved `at.bernhardberger.tvheadend:htsp:0.7.0` JAR is
  pinned at `gradle/verification-metadata.xml:642-643` with SHA-256
  `4243755adac1e86177b7d5a76ab2a8282790ff1dfe63f32c7e4f70179cf7e44e`.
  Its bytecode contains `FileStatRequest`, `FileStatResponse`, and the public
  `HtspFileRequestsKt.fileStat` extension. The required API is therefore in the
  released coordinate consumed by the default SDK build.
- `../tvheadend-upstream/src/htsp_server.c:2995-3042` maps `read(2) == 0` to a
  successful empty binary payload. The wire response alone cannot distinguish
  current EOF from final EOF.
- `../tvheadend-upstream/src/htsp_server.c:3084-3105` calls `fstat` on the same
  live file descriptor for every `fileStat`, so a handle can observe growth
  without reopening.
- `../tvheadend-upstream/src/htsp_server.c:3111-3150` supports absolute seek.

### DVR state can decide finality

The existing public DVR model contains the state needed to correlate file EOF.

- `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrRepository.kt:53-64,84-144,449-474,555-588`
  provides recording/completed states, file and data-size observations, fresh
  versus stale repository state, and a per-entry flow.
- `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:1240-1346`
  and
  `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/metadata/DvrReducer.kt:142-223`
  already map and reduce DVR state, files, and data size.
- On the ordinary non-clone path,
  `../tvheadend-upstream/src/dvr/dvr_db.c:3085-3103` stops and joins recording
  before marking the entry complete. On the clone path, the muxer epilog at
  `../tvheadend-upstream/src/dvr/dvr_rec.c:1647` has already closed the muxer
  before `dvr_stop_recording(..., clone=1)` completes the original entry. The
  epilog closes and finalizes the file
  (`../tvheadend-upstream/src/dvr/dvr_rec.c:203-235,2083-2100`) before either
  path publishes `DVR_COMPLETED`
  (`../tvheadend-upstream/src/dvr/dvr_db.c:619-633`). On this pinned revision,
  completion is therefore a defensible finalization signal.
- Recording-size notifications are throttled to at most one per five seconds
  (`../tvheadend-upstream/src/dvr/dvr_rec.c:1333-1344,1870-1876,1924-1928`).
  DVR updates are useful wakeups but are too coarse to be the byte-availability
  authority; a growth reader must poll `fileStat`.

Freshness is load-bearing. Only `DvrRepositoryState.Current` from the active
session generation may decide final EOF. Stale or synchronizing metadata must
not do so.

### The SDK currently snapshots a completed file

- `sdk-playback/src/main/kotlin/at/bernhardberger/tvheadend/sdk/playback/RecordingFile.kt:56-105`
  exposes open-time `sizeBytes`, absolute reads, seek, and close, but no dynamic
  stat operation.
- `sdk-playback/src/main/kotlin/at/bernhardberger/tvheadend/sdk/playback/RecordingFileReader.kt:103-156,184-216`
  fixes its read window at open and treats an unknown-size empty read as final
  EOF.
- `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:635-733`
  does not map HTSP `fileStat` into the SDK recording transport.
- `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendRecordingDataSource.kt:67-86,116-135`
  uses a standard `ProgressiveMediaSource`, reports a finite open-time length
  when available, and immediately maps reader EOF to Media3 end of input.
- `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:270-300`
  explicitly defers `RECORDING` entries and admits only `COMPLETED` entries.

These semantics are correct for completed files and must remain unchanged for
the existing completed-recording source.

### Temporary EOF can be hidden for forward playback

Official Media3 1.11.0 source establishes that extractor EOF is permanent for
one progressive load:

- `ProgressiveMediaPeriod.ExtractingLoadable.load` exits normally when the
  extractor returns `RESULT_END_OF_INPUT`:
  <https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L1295-L1358>
- `onLoadCompleted` marks `loadingFinished`, after which `continueLoading`
  returns false:
  <https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L764-L802>
  and
  <https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L505-L519>.

Returning `C.LENGTH_UNSET` prevents the current file size from becoming a fixed
input boundary, but it does not make EOF retryable. A growing source must wait
inside `DataSource.read` and must not expose temporary EOF to its extractor.
Media3 loader cancellation interrupts the loading thread, so every wait and RPC
must be interruptible and cancellation-responsive. Manufacturing periodic I/O
errors is not equivalent: it delegates semantics to error-retry policy.

The existing completed-file bridge clears a pending loader-thread interrupt
before entering `runBlocking`
(`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendRecordingDataSource.kt:162-188`).
That is bounded to one transport operation today, but a growth path must not
reuse it unchanged for a 30-second wait. It must detect and preserve a pending
interrupt before entering any wait and fail promptly with interrupted I/O.

Duration remains unknown or extractor-dynamic during growth. Current byte size
must never be converted into final duration. Consequently an orderly exit is
fail-closed for watched marking while duration is unknown, while natural end
can mark watched only after fresh state is `COMPLETED`
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrProgress.kt:116-139`).

Container reads also need complete parser units. MPEG-TS requires a complete
188-byte packet; Matroska needs complete EBML headers and payloads; fragmented
MP4 needs complete atom headers and payloads. A transient partial unit must stay
hidden rather than being misreported as EOF.

### Stock active-recording seek is not available

The stock progressive extractors do not create a growing seek map for the
TVHeadend output forms evaluated here.

| Container | Active-file behavior in Media3 1.11.0 | Exact evidence |
|---|---|---|
| Built-in Matroska | The first Cluster without already advertised Cues emits `SeekMap.Unseekable`; `sentSeekMap` makes that decision final. TVHeadend writes Cues and final duration at close. | [MatroskaExtractor 834-865](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java#L834-L865), [925-950](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java#L925-L950), `../tvheadend-upstream/src/muxer/muxer_mkv.c:1306-1347` |
| MPEG-TS with unknown length | Unknown input length skips duration scanning and emits a permanent unseekable map. Later PCR-bearing packets do not enlarge it. TVHeadend's pass muxer appends packets without a final index. | [TsExtractor 441-448](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java#L441-L448), [551-569](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java#L551-L569), `../tvheadend-upstream/src/muxer/muxer_pass.c:548-617,624-752` |
| av-lib MP4 with extractor/index unresolved | If Media3 selects `FragmentedMp4Extractor` without a proven `sidx`/`mfra`, the first `moof`/`mdat` emits an unseekable map and appended fragments do not themselves build one. TVHeadend requests fragmentation, but the evaluated source proves neither a usable growing index nor which extractor `DefaultExtractorsFactory` selects from a partial active file. | [FragmentedMp4Extractor 220-225](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/FragmentedMp4Extractor.java#L220-L225), [697-715](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/FragmentedMp4Extractor.java#L697-L715), `../tvheadend-upstream/src/muxer/muxer_libav.c:350-452,509-527,597-733` |

P7-F1 confirmed that `ProgressiveMediaPeriod` receives later extractor seek maps
and uses them for a reopen and seek cycle, but the stock inputs do not produce
the required update. `seekToUs` clamps every nonzero request to zero while the
map is unseekable, before trying an in-buffer seek:
<https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L603-L637>.
Even already buffered media therefore does not satisfy the approved nonzero
seek requirement through the existing source.

The SDK constructs the source with `DefaultExtractorsFactory`; extractor
sniffing from a partial active file remains unproven for every other TVHeadend
profile. P7-F1 explicitly selected `TsExtractor` for one pass-through MPEG-TS
fixture and proved the wrapper path only for that profile. No other container or
codec configuration is supported by inference.

## Forward-Only Contract If Scope Is Revised

A narrowed implementation can support start-over and uninterrupted forward
tailing. It does not satisfy the current P7-2 seek requirement.

1. Admit only an explicit growing `START_OVER`. Continue returning a typed
   defer/refusal for growing `RESUME`; never reinterpret the existing default
   resume request as start-over.
2. Return `C.LENGTH_UNSET` from the growth-specific data source. Keep the
   existing completed-file data source unchanged.
3. After an empty read, issue `fileStat`. If a known size has advanced beyond
   the absolute read position, permit exactly one immediate re-read. If that
   read is also empty, count it as no progress and re-enter the throttled wait;
   do not repeat an unbounded read/stat loop.
4. While fresh DVR state remains `RECORDING`, wait interruptibly for either a
   state update or a 500 ms timer, then stat again. Limit polling to two stat
   requests per second and enforce a 30-second monotonic no-progress budget.
   An omitted stat size is no progress, not evidence of finality, and follows
   the same throttled wait while recording.
5. A no-progress timeout reuses `RecordingFileFailure.TIMEOUT`; it is never
   EOF and adds no public failure enum value. Session-generation loss is
   `CONNECTION_CHANGED`; stale DVR metadata never decides finality.
6. Once fresh state is `COMPLETED`, issue a final stat and publish the completed
   state to terminal-progress logic. If final size is known, drain through it;
   if size is omitted, continue until the next empty read. Completion makes
   that empty read final. Only then return final EOF.
7. File shrink, disappearance, malformed progress, or an unproven physical-file
   identity change is `FILE_UNAVAILABLE`.
8. Thread interruption and coroutine cancellation abort waits promptly. A
   pending interrupt observed between data-source reads must be detected before
   `runBlocking`, must not be cleared to enter the wait, and must surface as
   interrupted I/O. Close remains cleanup-safe.
9. Reconnect, if included, owns a bounded reopen on the new generation at the
   last committed absolute byte position. It must not assume Media3's default
   error policy provides this contract.

Temporary EOF must never reach `Player.STATE_ENDED`. Final natural end may mark
watched only after the coordinator has observed a fresh `COMPLETED` state;
otherwise progress remains fail-closed.

## Rollover And Identity Risk

A DVR entry can own more than one physical file. TVHeadend appends file entries
when creating a new recording file
(`../tvheadend-upstream/src/dvr/dvr_rec.c:1230-1312`) and can close one muxer and
start another after a failed stream reconfiguration
(`../tvheadend-upstream/src/dvr/dvr_rec.c:1625-1673`). `dvr/<id>` resolves to the
last filename (`../tvheadend-upstream/src/dvr/dvr_db.c:5161-5171` and
`../tvheadend-upstream/src/htsp_server.c:2959-2973`).

An in-generation open handle remains tied to its original file, but reconnect
and reopen by DVR ID can switch to a new physical file. A single absolute byte
offset is then invalid. P7-2 must either model ordered multi-file recordings
with stable identity or explicitly reject rollover. Size and second-resolution
mtime alone are not a sufficient identity proof.

When `dvr_clone` is enabled, a failed stream reconfiguration can also stop the
watched DVR entry and continue recording under a newly cloned entry ID
(`../tvheadend-upstream/src/dvr/dvr_rec.c:1650-1653` and
`../tvheadend-upstream/src/dvr/dvr_db.c:1366-1393`). The original entry can
therefore become completed while the programme continues elsewhere. Playback
must not silently follow that new ID without an explicit stable-link contract;
otherwise final EOF applies to the original entry.

## Affected Modules And API Implications

| Module | Likely responsibility | API implication |
|---|---|---|
| `sdk-playback` | Dynamic stat result and a growth-aware absolute-offset reader/state machine. | `RecordingFile` is a public opt-in interface. Adding `stat()` changes its ABI and affects external implementations unless a compatible capability seam is designed. Choose that seam only in a separately approved implementation package. Reuse existing `TIMEOUT`; do not add a failure enum value. |
| `sdk-core` | Map gateway `fileStat`, generation-fence it, correlate fresh DVR state, and detect/reject file identity changes. | Existing public `DvrEntry`, `DvrRepositoryState`, and `entry(id)` are sufficient; no new public DVR state is currently justified. |
| `sdk-media3` | Growth-specific data source, interruptible wait/poll/reconnect owner, thin `TsExtractor` wrapper, bounded packet/time index, dynamic completion handoff, and terminal-progress behavior. The existing `blockingIo` bridge cannot be reused unchanged because it clears a pending interrupt before `runBlocking`. | Keep `createTvheadendRecordingMediaSource(RecordingFileOpener, ...)` completed-only: an opener alone lacks fresh DVR finality. Prefer coordinator-owned growth wiring and opt in only the proven pass-through TS path. Public admission/result documentation changes if growth is supported. |
| `sdk-testing` | Script dynamic size, empty reads, DVR transitions, generation replacement, reconnect, timeout, and rollover. | Additive fake behavior only; no protocol type should cross the SDK boundary. |
| `tvheadend-htsp` | None on current evidence. | Typed `fileStat` is already released in 0.7.0; no release package is required. |

P7-F1 selects the wrapper path as the smallest maintainable architecture:

1. A growth-specific data source owns stat/finality, returns unknown length, and
   withholds partial packets and temporary EOF.
2. An explicit TS `ExtractorsFactory` supplies a thin SDK wrapper around
   Media3's maintained `TsExtractor` only for the supported pass-through path.
3. The wrapper forwards parser output, retains a bounded index of Media3-reported
   video keyframe timestamps and conservative packet-aligned byte positions,
   and emits estimated-unseekable then estimated-seekable map updates.
4. Stock `ProgressiveMediaSource` and `ProgressiveMediaPeriod` own preparation,
   track selection, buffering, reopen, and extractor seek. No offset-rebased
   re-prepare, external parser library, parser fork, or custom `MediaPeriod` is
   justified by the P7-F1 evidence.

Production work must bound index memory and validate the conservative pre-roll
across a wider TS fixture matrix. P7-F1's 512-packet pre-roll is a successful
fixture-specific value, not a public constant or universal safety proof. Its
budget also absorbs the difference between the `Extractor.read` boundary where
the prototype samples `input.position` and the packet Media3 is parsing;
production sizing must separate that observation skew from PAT/PMT, PES, codec,
and GOP pre-roll needs.

The estimated map's duration is the latest indexed keyframe horizon, not a final
recording duration. Media3 initially sees the unknown-duration placeholder as
live and then sees finite, growing estimated durations. P7-F3/F4 must explicitly
test that timeline transition and prevent the indexed horizon from being used as
recording finality, completion, or progress evidence.

## Required Tests

Deterministic JVM and Android coverage must include:

- typed stat success, omitted fields while recording and completed, malformed
  replies, unsupported protocol, deadline, cancellation, and stale-generation
  outcomes;
- clock-driven 500 ms polling with a hard two-request-per-second ceiling and no
  busy loop, including stat advance followed by another empty read;
- empty read followed by growth, repeated empty reads, short reads, and
  completion before/after the final stat;
- partial TS packets that do not leak EOF or malformed input; add equivalent
  container-unit tests only if another container is separately supported;
- 30-second no-progress timeout as `TIMEOUT`, cancellation during every
  wait/RPC, a pending interrupt arriving between reads, size shrink, file
  disappearance, and physical-file rollover;
- generation loss and bounded reopen at the last committed offset, including
  refusal when identity continuity is not proven;
- same-entry multi-file rollover and `dvr_clone` continuation under a new DVR
  entry ID;
- no `STATE_ENDED`, watched mutation, or progress clearing at temporary EOF;
- completed-state handoff before real EOF and correct final watched/progress
  behavior;
- unknown/dynamic growing duration, fail-closed orderly exit, and completed
  natural end;
- explicit refusal of growing `RESUME` unless separate evidence later proves a
  safe rule;
- explicit wrapper selection plus real Media3 extractor append/seek fixtures
  for every supported TS codec and programme-layout variant; and
- coordinator races among state updates, player error/end, cancellation,
  reconnect, and target replacement.

P7-3 remains a real-device and real-server acceptance package. It must use a
disposable active recording and verify start, continued growth, catch-up,
bounded waiting, new-byte delivery, the selected seek contract, reconnect,
completion, no false terminal/progress behavior, and cleanup. Unit tests cannot
complete that gate.

## Stop-Gated Package Options And Estimate

### Option A: approve the bounded pass-through TS requirement

| Package | Scope | Effort | Estimate |
|---|---|---|---|
| P7-F1 | Test-only seek feasibility for one named TVHeadend container and Media3 1.11.0. | `max` | Complete; wrapper path proven |
| P7-F2 | Dynamic stat transport and temporary/final EOF state machine. | `max` | 4-6 engineer-days |
| P7-F3 | Implement and harden the thin TS wrapper, bounded index, conservative seek points, and growth source integration for the selected container. | `max` | 6-10 engineer-days |
| P7-F4 | Coordinator admission, reconnect, completion, watched/progress, docs, ABI, and publication integration. | `max` | 5-8 engineer-days |
| P7-F5 | Real server/device acceptance and cleanup. | `max` | 4-6 engineer-days |

Revised bounded remaining total for the proven container: **19-30
engineer-days**. P7-1's pre-feasibility rows assigned 4-6 days to P7-F2, 10-18
to P7-F3, 5-8 to P7-F4, and 4-6 to P7-F5, or 23-38 engineer-days remaining after
P7-F1. The reduction applies only to P7-F3 because a parser fork and custom
`MediaPeriod` are no longer expected. Supporting built-in Matroska or av-lib MP4
would require separate feasibility and estimation. Each row remains stop-gated,
and none is authorized by this document.

### Option B: revise to forward-only start-over

| Package | Scope and stop gate | Effort | Estimate |
|---|---|---|---|
| P7-N1 | Dynamic stat transport and forward-only temporary/final EOF reader, including clock, cancellation, reconnect, and rollover tests. Stop unless bounded polling and prompt cancellation are deterministic. | `max` | 4-6 engineer-days |
| P7-N2 | Growth data source/coordinator, loader-interrupt bridge, dynamic duration/completion/progress behavior, append fixtures, docs, ABI, and publication checks. Stop unless temporary EOF produces no player end or watched mutation. | `max` | 4-6 engineer-days |
| P7-N3 | Disposable real-server/device start, tail, bounded wait, reconnect, completion, progress, and cleanup acceptance. | `max` | 4-6 engineer-days |

Bounded total: **12-18 engineer-days**. This option excludes growing-recording
seek and resume and therefore requires an explicit acceptance revision before
P7-N1 starts. Each row depends on successful completion of the preceding row.

### Option C: defer or reject

Keep completed-recording playback and the existing typed
`GROWING_RECORDING_DEFERRED` outcome. This remains the default until the operator
accepts either Option A's bounded TS cost/risk or Option B's narrower product
behavior.

## Recommendation

P7-F1 supplies enough evidence to consider a narrowly revised P7-2, but it does
not authorize production implementation. Choose one of the following
explicitly:

1. approve and scope a pass-through MPEG-TS-only implementation around the thin
   `TsExtractor` wrapper and revised 19-30 engineer-day remaining estimate;
2. revise P7-2/P7-3 acceptance to forward-only start-over and approve Option B;
3. defer or reject growing-recording playback and retain current behavior.

No later Phase 7 package starts from completion of this evaluation alone.
