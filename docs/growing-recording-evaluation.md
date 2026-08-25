# P7-1 Growing Recording Semantics Evaluation

Date: 2026-08-25

## Decision

**NO-GO for P7-2 as currently proposed.**

The SDK and HTSP layers can detect file growth and distinguish temporary from
final EOF. The existing Media3 1.11.0 progressive stack can also continue
forward playback if a custom data source withholds temporary EOF. It cannot,
however, provide the required safe nonzero seek for active TS or built-in
Matroska, and no safe path is proven for TVHeadend's av-lib MP4 output.

The full requirement therefore needs a seek architecture beyond the stock
progressive source, probably with container-specific byte/time index ownership.
Candidate shapes include an offset-rebased re-prepare, a wrapper that reuses
official extractor output, or a custom `MediaSource`/`MediaPeriod`; P7-1 does
not select among them. This is a new high-risk playback subsystem, not a
bounded extension of the completed-file reader. Production P7-2 should not
start without either:

1. revising the requirement to forward-only start-over playback, with no
   growing-recording seek or resume; or
2. approving a test-only feasibility package that proves one bounded seek
   architecture for an explicitly selected TVHeadend container before any
   production API is chosen.

P7-2 and P7-3 remain behind the explicit operator decision gate.

## Scope And Sources

This is the evidence-only evaluation recorded for the maintainers' Phase 7
decision gate. It makes no production API, implementation, test, build,
dependency, or ABI change.

SDK source paths below are relative to the repository root. HTSP and pinned
TVHeadend paths refer to maintainer-local sibling checkouts used for
exact-version evidence; those checkouts are not shipped with this repository.

Evidence was taken from:

- the SDK at commit `e16680eb8fcb88aa047f3bfb6534500862b606ce`;
- `tvheadend-htsp` 0.7.0 source and tests;
- pinned TVHeadend source revision
  `27295c5a48f2c575678bb224014cb9a26a773083`; and
- official AndroidX Media3 1.11.0 source at commit
  `2bc207851df311340767e913931ca7b28cab1794`.

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

### Safe active-recording seek is not available

The stock progressive extractors do not create a growing seek map for the
TVHeadend output forms evaluated here.

| Container | Active-file behavior in Media3 1.11.0 | Exact evidence |
|---|---|---|
| Built-in Matroska | The first Cluster without already advertised Cues emits `SeekMap.Unseekable`; `sentSeekMap` makes that decision final. TVHeadend writes Cues and final duration at close. | [MatroskaExtractor 834-865](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java#L834-L865), [925-950](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java#L925-L950), `../tvheadend-upstream/src/muxer/muxer_mkv.c:1306-1347` |
| MPEG-TS with unknown length | Unknown input length skips duration scanning and emits a permanent unseekable map. Later PCR-bearing packets do not enlarge it. TVHeadend's pass muxer appends packets without a final index. | [TsExtractor 441-448](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java#L441-L448), [551-569](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java#L551-L569), `../tvheadend-upstream/src/muxer/muxer_pass.c:548-617,624-752` |
| av-lib MP4 with extractor/index unresolved | If Media3 selects `FragmentedMp4Extractor` without a proven `sidx`/`mfra`, the first `moof`/`mdat` emits an unseekable map and appended fragments do not themselves build one. TVHeadend requests fragmentation, but the evaluated source proves neither a usable growing index nor which extractor `DefaultExtractorsFactory` selects from a partial active file. | [FragmentedMp4Extractor 220-225](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/FragmentedMp4Extractor.java#L220-L225), [697-715](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/FragmentedMp4Extractor.java#L697-L715), `../tvheadend-upstream/src/muxer/muxer_libav.c:350-452,509-527,597-733` |

`ProgressiveMediaPeriod` can mechanically receive a later extractor seek map,
but these inputs do not produce the required update. More importantly,
`seekToUs` clamps every nonzero request to zero while the map is unseekable,
before trying an in-buffer seek:
<https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L603-L637>.
Even already buffered media therefore does not satisfy the approved nonzero
seek requirement through the existing source.

The SDK constructs the source with `DefaultExtractorsFactory`; extractor
sniffing from a partial active file has not been proven for each TVHeadend
profile. P7-F1 must establish the selected extractor and seek behavior from
incrementally appended real-format fixtures before any container is supported.

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
| `sdk-playback` | Dynamic stat result and a growth-aware absolute-offset reader/state machine. | `RecordingFile` is a public opt-in interface. Adding `stat()` changes its ABI and affects external implementations unless a compatible capability seam is designed. Do not choose that seam until feasibility is proven. Reuse existing `TIMEOUT`; do not add a failure enum value. |
| `sdk-core` | Map gateway `fileStat`, generation-fence it, correlate fresh DVR state, and detect/reject file identity changes. | Existing public `DvrEntry`, `DvrRepositoryState`, and `entry(id)` are sufficient; no new public DVR state is currently justified. |
| `sdk-media3` | Growth-specific data source, interruptible wait/poll/reconnect owner, dynamic completion handoff, and terminal-progress behavior. The existing `blockingIo` bridge cannot be reused unchanged because it clears a pending interrupt before `runBlocking`. Full scope also needs a seek architecture beyond the stock progressive source. | Keep `createTvheadendRecordingMediaSource(RecordingFileOpener, ...)` completed-only: an opener alone lacks fresh DVR finality. Prefer coordinator-owned growth wiring. Public admission/result documentation changes if growth is supported. |
| `sdk-testing` | Script dynamic size, empty reads, DVR transitions, generation replacement, reconnect, timeout, and rollover. | Additive fake behavior only; no protocol type should cross the SDK boundary. |
| `tvheadend-htsp` | None on current evidence. | Typed `fileStat` is already released in 0.7.0; no release package is required. |

P7-F1 must compare an offset-rebased re-prepare using an SDK-owned byte/time
index, a wrapper that reuses official extractor output, a maintained external
library, and a custom source/period. Before selecting any path, it must prove
that container interpretation can reuse maintained code rather than silently
introducing a second production parser for TS, EBML, or ISO-BMFF.

## Required Tests

Deterministic JVM and Android coverage must include:

- typed stat success, omitted fields while recording and completed, malformed
  replies, unsupported protocol, deadline, cancellation, and stale-generation
  outcomes;
- clock-driven 500 ms polling with a hard two-request-per-second ceiling and no
  busy loop, including stat advance followed by another empty read;
- empty read followed by growth, repeated empty reads, short reads, and
  completion before/after the final stat;
- partial TS packet, EBML element, and MP4 atom writes that do not leak EOF or
  malformed input;
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
- `DefaultExtractorsFactory` selection from partial input plus real Media3
  extractor append fixtures for every supported container; and
- coordinator races among state updates, player error/end, cancellation,
  reconnect, and target replacement.

P7-3 remains a real-device and real-server acceptance package. It must use a
disposable active recording and verify start, continued growth, catch-up,
bounded waiting, new-byte delivery, the selected seek contract, reconnect,
completion, no false terminal/progress behavior, and cleanup. Unit tests cannot
complete that gate.

## Stop-Gated Package Options And Estimate

### Option A: retain the full requirement

| Package | Scope | Effort | Estimate |
|---|---|---|---|
| P7-F1 | Test-only seek feasibility for one named TVHeadend container and Media3 1.11.0. Prove partial-input extractor selection and compare offset-rebased re-prepare, extractor-wrapper, maintained-library, and custom source/period paths. No production API; stop if no safe maintained strategy is proven. | `max` | 4-6 engineer-days |
| P7-F2 | Dynamic stat transport and temporary/final EOF state machine. | `max` | 4-6 engineer-days |
| P7-F3 | Implement the growth and seek architecture proven by P7-F1 for the selected container. | `max` | 10-18 engineer-days |
| P7-F4 | Coordinator admission, reconnect, completion, watched/progress, docs, ABI, and publication integration. | `max` | 5-8 engineer-days |
| P7-F5 | Real server/device acceptance and cleanup. | `max` | 4-6 engineer-days |

Bounded total for one proven container: **27-44 engineer-days**. Supporting TS,
built-in Matroska, and av-lib MP4 as one contract would exceed this range and
must be separately estimated after P7-F1 identifies reusable index ownership.
The estimate is deliberately stop-gated because the seek architecture may be a
technical no-go. P7-F3 must be re-estimated if P7-F1 disproves its sizing
assumptions.

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
`GROWING_RECORDING_DEFERRED` outcome. This is the recommended default unless the
operator accepts either Option A's feasibility cost/risk or Option B's narrower
product behavior.

## Recommendation

Do not authorize the current P7-2 row as production implementation. Choose one
of the following explicitly:

1. approve only P7-F1 as a test-only, single-container feasibility package;
2. revise P7-2/P7-3 acceptance to forward-only start-over and approve Option B;
3. defer or reject growing-recording playback and retain current behavior.

No later Phase 7 package starts from completion of this evaluation alone.
