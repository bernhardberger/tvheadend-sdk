# TVHeadend Kotlin SDK

[![CI](https://github.com/bernhardberger/tvheadend-sdk/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bernhardberger/tvheadend-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/at.bernhardberger.tvheadend/sdk-core)](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/sdk-core)
[![License: GPLv3](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

This independently maintained GPLv3 Kotlin SDK provides JVM and Android
libraries for applications that connect to TVHeadend. It is not official
TVHeadend software.

The SDK is split into five libraries:

| Artifact | Platform | Purpose |
|---|---|---|
| `sdk-core` | Kotlin/JVM | Protocol integration, session lifecycle, models, metadata, EPG, and DVR |
| `sdk-playback` | Kotlin/JVM | Subscription, seek, timeshift, and timestamp state machines |
| `sdk-media3` | Android | Media3 sources, stream readers, and playback coordination |
| `sdk-android` | Android | Discovery, connectivity, credential storage, and authenticated artwork |
| `sdk-testing` | Kotlin/JVM | Fakes, repositories, scripted events, and packet fixtures |

Release `0.1.2` is available from Maven Central. The normal build never
publishes. `./gradlew clean build check stageLocalPublication` verifies the
repository and stages all five modules under `build/local-maven`; local staging
does not establish Maven Central availability.

Applications can select only the modules they need. For example:

```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:sdk-media3:0.1.2")
}
```

See [versioning](docs/versioning.md) for the provisional 0.x compatibility
policy and [releasing](docs/releasing.md) for the publication trust boundary.

The default build resolves
`at.bernhardberger.tvheadend:htsp:0.7.0` from Maven Central. Maintainers working
across adjacent checkouts may explicitly opt into source substitution with
`-Ptvheadend.htsp.composite=true`; CI and release builds do not use that
property.

## Staged session readiness

`SessionState.Synchronizing` admits live playback only for channel IDs from a
retained same-process catalog when the server has not denied streaming. A cold
session has no channel ID to validate and returns `SubscriptionOpenResult.NotReady`.
DVR mutations and recording progress remain gated on `SessionState.Ready`.

`Ready` follows the server's authoritative initial metadata fence. EPG coverage
queries and DVR configuration and disk-space enrichment continue as supervised
generation-owned background work and do not delay or tear down readiness.
Consumers can prioritize one bounded EPG horizon without waiting for network
work:

```kotlin
val result = session.epgRepository.requestCoverage(channel.id, programme.stop)
```

`EpgCoverageRequestResult` distinguishes existing coverage, an accepted or
deduplicated hint, an ineligible request, and loss of the owning generation.

See [the build matrix](docs/build-matrix.md) for the pinned toolchain and test
runtime split. The optional decoder fallback's exact source, build, checksums,
and redistribution requirements are recorded in the
[FFmpeg contingency notes](docs/ffmpeg-contingency.md). Phase 4 real-device
acceptance is recorded in the
[playback verification notes](docs/playback-device-verification.md). Phase 5
process, credential, connectivity, and NSD acceptance is recorded in the
[Android lifecycle verification notes](docs/android-lifecycle-verification.md).
Phase 6 end-to-end SDK, staged-consumer, and cross-process recording acceptance
is recorded in the [SDK acceptance notes](docs/sdk-acceptance.md). The Phase 7
initial no-go and bounded pass-through MPEG-TS wrapper feasibility result are in
the [growing-recording evaluation](docs/growing-recording-evaluation.md).

## Authenticated artwork

`sdk-android` supplies an opaque Coil model and custom fetcher for HTSP
`imagecache` entries. Register the component on an application-owned Coil 3
loader, then create models from channel, tag, or rating icon metadata:

```kotlin
val imageLoader = ImageLoader.Builder(context)
    .components {
        add(createTvheadendArtworkFetcherFactory())
    }
    .build()

val artwork = TvheadendArtwork.create(session, channel.icon)
```

The model rejects external URLs and malformed selectors. The SDK deliberately
does not install a path-derived Coil key, so authenticated selectors do not
enter memory-cache or disk-cache keys. Coil owns decoding and closes every
successfully returned image source. TVHeadend requires recorder access for this
authenticated file API; otherwise loads report `ACCESS_DENIED`.

## Recording progress

`TvheadendSession.recordingProgressCapability` is `SUPPORTED` only when the
current ready generation can close recording files without changing play count
and can report position and watched state separately. Unknown and pre-v27
connections fail closed; there is no degraded fallback.

`DvrProgressPolicy` offers every positive saved position for completed
recordings. Its pure tracker uses one 30-second elapsed cadence and reports any
positive movement, plus explicit pause and terminal observations. Natural end
marks a completed recording watched; an orderly exit requires at least 95% of a
known positive actual media duration. Errors and growing recordings never infer
completion.

`DvrRepository.reportProgress` is an uncoordinated low-level RPC. Direct callers
must serialize reports and preserve observation and terminal ordering
themselves; the SDK does not persist or replay pending progress.

## Media3 playback coordination

`sdk-media3` provides a narrow coordinator for live channels, completed
recordings, and the bounded growing pass-through MPEG-TS path. The application
owns the `ExoPlayer` and the coroutine running the coordinator. Source changes,
resume registration, recovery, and progress observation are serialized on the
player's application looper; the coordinator
does not create or release the player or own a service, MediaSession, audio
focus, notification, surface, autoplay, navigation, or presentation policy.

```kotlin
val coordinator = createTvheadendPlaybackCoordinator(session, player)
val owner = applicationScope.launch { coordinator.run() }

coordinator.setLiveTarget(
    channel.id,
    LivePlaybackOptions(timeshiftPeriod = 30.minutes),
)
coordinator.seekTimeshift((-30).seconds)
coordinator.returnToLive() // bounded near-live position, not exact live mode
coordinator.pauseTimeshift() // pauses server delivery only
coordinator.resumeTimeshift()
coordinator.setRecordingTarget(recording.id, RecordingPlaybackStart.RESUME)
coordinator.setRecordingTarget(activeRecording.id, RecordingPlaybackStart.START_OVER)

coordinator.shutdown(2.seconds)
owner.join()
session.shutdown()
player.release()
```

`TvheadendPlaybackCoordinator.timeshiftState` reports only the current live
target's positive server grant and ordered server observations. Buffered
duration, position behind live, and server pause state remain `null` until valid
status events arrive. Timeshift pause and resume send server speeds `0` and
`100`; ordinary Media3 play/pause remains application-owned.

`TvheadendPlaybackCoordinator.subscriptionIssue` reports only the current live
target's canonical TVHeadend issue. Known server codes map to the safe
`SubscriptionIssue` enum; unknown or localized values become `UNKNOWN`, and raw
server text is never exposed. The state clears on period retry, target
replacement, recording playback, stop, and shutdown.

Direct live playback feeds TVHeadend's packet-level `AAC` stream to Media3's
maintained `AdtsReader`. TVHeadend normalizes AAC-LATM packets to ADTS before
HTSP delivery, so the SDK does not infer the source transport framing or expose
a separate LATM codec type. This path has deterministic packet-fixture coverage;
the current acceptance server has no AAC service, so it is not a live-server AAC
claim.

All recording targets require the current semantic
`RecordingProgressCapability.SUPPORTED`; unknown and pre-v27 connections are
refused before source creation. An active target must have one stable `.ts`
file and must use explicit `START_OVER`. `RESUME` returns
`GROWING_RECORDING_RESUME_UNSUPPORTED`; other active containers remain
`GROWING_RECORDING_DEFERRED`. Growing seek is approximate and becomes available
only after the maintained `TsExtractor` wrapper has validated MPEG-2, H.264, or
HEVC and indexed already parsed keyframes. Other TS codecs remain forward-only.

Temporary EOF stays inside the growth reader and never marks the recording
watched. Growing progress is best-effort and generation-local with one RPC in
flight and one latest pending observation. The dynamic indexed horizon is not a
final duration, so orderly replacement never uses it to infer watched state.
Natural end may mark watched only after both fresh completion and final EOF are
proven. One target-scoped growth lease binds the original connection generation,
DVR incarnation, and physical file across every seek or loader retry. Progress
RPC admission revalidates that lease after capability and generation selection,
then uses its original generation rather than whichever generation is current
later. A continuity change therefore fails through the typed
`TvheadendRecordingException` path rather than rebinding, following a clone, or
carrying an offset. Target replacement never waits for a progress RPC. Explicit
shutdown may drain one pending report for a caller-supplied timeout of at most
ten seconds before the application shuts down the session and releases the
player.
