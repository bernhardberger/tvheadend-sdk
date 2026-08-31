# SDK module map

Use this map before broad repository exploration. Start with the named entry
point and search directly for its callers or tests. Delegate mapping only when a
question still spans an unknown flow after these paths are checked.

| Concern | Owner | Start here |
|---|---|---|
| Session lifecycle and atomic observations | `sdk-core` | `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt`, `SessionObservation.kt`, then `session/ConnectionOwner.kt` |
| Channels and tags | `sdk-core` | `ChannelRepository.kt`, then `metadata/ChannelTagReducer.kt` |
| EPG acquisition and reduction | `sdk-core` | `EpgRepository.kt`, `session/EpgWorker.kt`, then `metadata/EpgReducer.kt` |
| DVR state, mutations, progress, and cutpoints | `sdk-core` | `DvrRepository.kt`, `DvrMutations.kt`, `DvrProgress.kt`, and `DvrCutpoints.kt` |
| HTSP mapping | `sdk-core` gateway implementation | `gateway/ProtocolGateway.kt`, then `gateway/htsp/HtspProtocolGateway.kt`; wire defects belong in the HTSP repository |
| Subscription, seek, timeshift, and timestamps | `sdk-playback` | `SubscriptionStateMachine.kt`, `SubscriptionTimestampRebasing.kt`, and `RecordingFileReader.kt` |
| Media3 application boundary | `sdk-media3` | `TvheadendPlaybackCoordinator.kt`, then `PlaybackCoordinatorInternals.kt` |
| Live Media3 source and periods | `sdk-media3` | `TvheadendLiveMediaSource.kt`, `TvheadendLiveMediaPeriod.kt`, and `LiveTimeshiftControls.kt` |
| Recording and growing playback | `sdk-media3` | `TvheadendRecordingDataSource.kt`, `TvheadendGrowingRecordingDataSource.kt`, and `GrowingTsExtractor.kt` |
| Decoder and elementary streams | `sdk-media3` | `TvheadendDecoderPolicy.kt`, `ElementaryStreamReaderFactory.kt`, and `SubscriptionElementaryStreamAdapter.kt` |
| Android discovery and connectivity | `sdk-android` | `TvheadendDiscovery.kt` and `TvheadendConnectivity.kt` |
| Profiles, credentials, and artwork | `sdk-android` | `TvheadendServerProfileStore.kt`, `TvheadendCredentialStore.kt`, and `TvheadendArtwork.kt` |
| Consumer fakes and scripted protocol events | `sdk-testing` | `FakeSessionObservation.kt` and `ScriptedSubscriptionConnection.kt` |
| Published-coordinate contract | `consumer-contract` | `consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/sdk/consumer/StagedSdkConsumer.kt` |

## Commands and release tooling

- Build and test through the installed `gradle-run` skill. Do not stream full
  Gradle output or start a second Gradle workflow concurrently.
- Use `tools/sdk-device --help` for SDK instrumentation operations. Credential
  provisioning is intentionally outside that tool.
- For releases, read `docs/releasing.md`, then use command `--help`. Do not read
  the 1,000-line tool implementations unless editing them or diagnosing a
  failure already attributed to the tool.
- Run `tools/publish-central-release --check-setup` once per release package.
  Preserve every immutable-tag, single-upload, credential, and stop gate in
  `docs/releasing.md`.
