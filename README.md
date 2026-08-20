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
`at.bernhardberger.tvheadend:htsp:0.4.0` from Maven Central. Maintainers working
across adjacent checkouts may explicitly opt into source substitution with
`-Ptvheadend.htsp.composite=true`; CI and release builds do not use that
property.

See [the build matrix](docs/build-matrix.md) for the pinned toolchain and test
runtime split.
