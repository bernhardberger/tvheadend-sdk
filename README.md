# TVHeadend SDK

An independently maintained GPLv3 Kotlin SDK for applications that communicate
with TVHeadend. This project is not official TVHeadend software.

The repository is split into five libraries:

- `sdk-core`: protocol integration, lifecycle, models, metadata, EPG, and DVR.
- `sdk-playback`: subscription, seek, timeshift, and timestamp state machines.
- `sdk-media3`: Android Media3 playback adapters.
- `sdk-android`: Android discovery, connectivity, credentials, and artwork.
- `sdk-testing`: JVM-only SDK test fakes and fixtures.

The initial development version is `0.1.0-SNAPSHOT`. Nothing is published by
the normal build. `./gradlew clean build check stageLocalPublication` verifies
the repository and stages all five modules under `build/local-maven`.

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

`sdk-media3` provides a narrow coordinator for live channels and completed
recordings. The application owns the `ExoPlayer` and the coroutine running the
coordinator. Source changes, resume registration, recovery, and progress
observation are serialized on the player's application looper; the coordinator
does not create or release the player or own a service, MediaSession, audio
focus, notification, surface, autoplay, navigation, or presentation policy.

```kotlin
val coordinator = createTvheadendPlaybackCoordinator(session, player)
val owner = applicationScope.launch { coordinator.run() }

coordinator.setLiveTarget(channel.id)
coordinator.setRecordingTarget(recording.id, RecordingPlaybackStart.RESUME)

coordinator.shutdown(2.seconds)
owner.join()
session.shutdown()
player.release()
```

Completed recordings require the current semantic
`RecordingProgressCapability.SUPPORTED`; unknown and pre-v27 connections are
refused before source creation, and growing recordings remain explicitly
deferred. Progress is best-effort and generation-local with one RPC in flight
and one latest pending observation. Target replacement never waits for that
RPC. Explicit shutdown may drain it for a caller-supplied timeout of at most ten
seconds before the application shuts down the session and releases the player.
