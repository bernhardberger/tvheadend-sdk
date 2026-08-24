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

See [the build matrix](docs/build-matrix.md) for the pinned toolchain and test
runtime split. The optional decoder fallback's exact source, build, checksums,
and redistribution requirements are recorded in the
[FFmpeg contingency notes](docs/ffmpeg-contingency.md). Phase 4 real-device
acceptance is recorded in the
[playback verification notes](docs/playback-device-verification.md). Phase 5
process, credential, connectivity, and NSD acceptance is recorded in the
[Android lifecycle verification notes](docs/android-lifecycle-verification.md).

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
