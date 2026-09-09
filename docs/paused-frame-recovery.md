# Finite paused frame recovery

P42-S2, 2026-09-09. Baseline `db213c78b7c5e7d73d9e1f3f3cf4a1483b2aa1db`.
The immutable P42-S1 result `527aab22232d823fc3d2f8f474cdb417` and unmerged
`p42-s1-blocked-evidence` commit `8032e964cff4fda822782a659818eaa4b1560876`
remain investigation evidence. They are not the source of SDK 0.12.0 artifacts.

## Maintained lifecycle

The pinned [MediaCodecRenderer 1.11.0 source](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java)
was compared with the locally extracted source; both SHA-256 values were
`d6c42a8080a299650f36c818cb9949b016c1b65061ea486b0dc8fb5428b3dd55`.

- `shouldReinitCodec()` is protected (660-666) and checked before the next source
  read (1502-1509). `onQueueInputBuffer` precedes codec submission (1687-1702);
  successful feed then sets `codecReceivedBuffers` (1712-1716) before another hook
  invocation. It is not itself a rendered-frame acknowledgement.
- `drainAndReinitializeCodec` (2227-2234) requests Media3-owned drain and
  reinitialization. Internal codec EOS (1521-1535) releases buffered output through
  ordinary output processing (2241-2338).
- `processEndOfStream` (2436-2455) recreates the codec for `REINITIALIZE`, rather
  than setting `outputStreamEnded`. Recreation releases and initializes the codec
  (2616-2619). The fresh decoder skips non-keyframe input (1623-1637).
- The SDK does not signal source EOS, manipulate private codec state, fabricate
  format/audio data, modify SPS reorder requirements, or resume playback.

These semantics correct the premature conclusion that no maintained nonterminal
path existed. They did not, alone, prove paused rendering or continuation.

## Ownership and scope

After an accepted skip observed while the server is paused, the selected H.264
reader inspects the first I-labelled packet with maintained `NalUnitUtil` NAL
classification. An IDR with no non-IDR slice is eligible for the maintained
`H264Reader.endOfInputReached()` finite flush. The reader is not reset again;
continuation must preserve its original sample partitioning. A single emitted
sample enables the video discontinuity without marking missing audio ready.

The queue retains that sample until it has been read for the codec. On the next
input-feed hook it consumes a one-shot request and rewinds the actual SampleQueue
index. Media3 drains the old decoder; the fresh decoder receives the same retained
IDR and then subsequent source samples. No replay bytes are synthesized. Rewind
failure is an explicit safe error rather than silently losing references.

The period lock covers retention, queue reads, rewind and invalidation. Second
seek, interruption, release, failure, drop, or changed selection retires the
pending request. Output from an invalidated source is skipped using Media3's
protected output operation. Already submitted surface buffers cannot be recalled.
Normal audio buffering and application ownership of Player remain unchanged.
Applications must use `createTvheadendRenderersFactory` for this integration.

IDR NAL classification is not proof of a complete frame. Media3's public SPS
parser exposes `frameMbsOnlyFlag`, but its actual slice field/frame parser remains
private inside `H264Reader`. The SDK does not reproduce that parser. The recorded
fixture was separately inspected with maintained FFmpeg 7.1.5 `trace_headers`:
SPS/PPS, NAL type 5, slice type 7, `field_pic_flag=0`, MBAFF enabled, reorder 1,
reference buffer 4. It is a full-frame IDR, not a separate field. The runtime
evidence below does not establish behavior for missing slices, isolated fields,
non-IDR open GOPs, or other codecs/decoders. Non-IDR packets are not drained by
this path. Normal decoder validation still owns malformed media.

## Runtime evidence

The real scripted connection, subscription manager/rebaser, source/period,
elementary readers, SampleQueues, SDK renderer factory and asynchronous ExoPlayer
run on the standard identity-verified LXC119 API 36 Android TV emulator. No G10,
TVHeadend server, Player repository, harness or credential provisioning was used.

The finite regression first establishes a playing clock, pauses, accepts a seek,
then sends exactly the first recorded raw video packet without following video or
audio. It observes a new renderer first-frame callback AND a new image at Media3's
maintained `EGLSurfaceTexture` consumer, plus codec recreation. The clock stays
paused and only explicit test speed commands are present. No position/token is
injected as evidence of a frame.

An explicit test resume supplies dependent video individually while audio is
delayed; the player does not advance until audio arrives. Later video timestamps
and new surface images accompany the resumed clock. A second paused seek repeats
the finite path. Source replacement retires the old collector and mapping before
the replacement produces its own frame; release retires that collector too. The
fake rejects delivery to retired collectors, so this is collector cancellation
evidence, not a test that bypasses the fake to force delivery to a dead owner.
Unit tests separately invoke stale queue callbacks after second seek, interruption,
release and deselection and prove they cannot request a drain. They also verify
byte-identical one-shot replay, timestamp/keyframe identity and discard retention.

The reader regression compares all emitted timestamps, sizes and keyframe flags
through continuation and real end-of-input against the unflushed sequence. Surface
availability and timestamp continuation are not pixel comparisons or evidence of
numerical landing at the requested content coordinate. Both edge commands reuse
captured timestamps, testing mechanics rather than actual server seek placement.

The recording's audio ends before its first video; the reviewed P42-S1 cadence
extension is reused unchanged, preserving the raw A/V origins. It is repeated
captured audio in an offline fixture, not live server evidence or production fake
audio. The original paused test started paused and aligned both tracks before a
burst; it did not exercise finite asynchronous input after playing. Its bare
`SurfaceTexture(0)` did not consume images, so it could not establish successive
surface delivery. The new test uses the maintained EGL consumer.

## Precision and checks

The reviewed P42-S1 internal precision correction is integrated unchanged:
real `Player.currentPosition` snapshots have 1,000-us resolution, exact snapshots
retain 1 us, and mapping intersects only observed unambiguous timestamp ranges.
The finite regression proves exact mapping unavailable at the truncated first
sample while the real resolution maps the accepted seek. Precision is separate
from decoder output and cannot substitute for a frame.

`./gradlew build check :sdk-media3:assembleDebugAndroidTest` passed on JDK 21.
All 10 current `Media3SampleQueueInstrumentationTest` methods passed, no failures
or skips. Built and installed APK SHA-256:
`31b554a8e4e9dc2ed8d59e7d5fd4f23209f3e63a0973f487615a2b3544a1d541`.
This APK predates the version-only 0.12.1 preparation. The old evidence branch's
13-test count, including direct codec-EOS diagnostics, is not attributed to this
tree; that diagnostic was not copied or rerun.

Source analysis `ses_f7a11c3cfffesYM4g5JVExWt2B` used primary-supplied pinned
excerpts after its external read was denied. Public-API research
`ses_f7a0a2335ffe9Iqj7tGWwpxlwM` inspected pinned upstream NalUnitUtil/H264Reader.
Neither is an engineering-review verdict. Release and consumer availability
require their separate gates; this document alone makes no publication claim.
