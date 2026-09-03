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
| `sdk-android` | Android | Discovery, connectivity, atomic server-profile storage, and authenticated artwork |
| `sdk-testing` | Kotlin/JVM | Aggregate observation fakes, scripted events, and packet fixtures |

The source is configured for the `0.5.0` development line. The normal build never publishes.
`./gradlew clean build check stageLocalPublication` verifies the repository and
stages all five modules under `build/local-maven`; the Maven Central badge, not
local source or staging, reports the latest publicly available version.

Applications can select only the modules they need. For example:

```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:sdk-media3:<released-version>")
}
```

Replace `<released-version>` with the version reported by the Maven Central
badge. A checkout's configured version can be newer than its public artifacts.
See the [consumer API guide](docs/consumer-guide.md) for module selection and
end-to-end usage, [versioning](docs/versioning.md) for the provisional 0.x
compatibility policy, and [releasing](docs/releasing.md) for the publication
trust boundary.

## Server profile storage

`sdk-android` persists one selected server profile through the application-scoped
`TvheadendServerProfileStore`. Normal connection reads return password
authentication only inside an opaque, connectable `ServerProfile`:

```kotlin
val profiles = TvheadendServerProfileStore(context)
profiles.storePassword(
    host = enteredHost,
    port = enteredPort,
    username = enteredUsername,
    password = enteredPassword,
)

when (val stored = profiles.loadProfile()) {
    ServerProfileReadResult.Missing -> showSetup()
    ServerProfileReadResult.Unavailable -> showStorageUnavailable()
    is ServerProfileReadResult.Available -> session.connect(stored.profile)
}
```

Settings screens can call `loadProfileForEditing()` to distinguish missing,
unavailable, anonymous, and password profiles. Anonymous and password results
expose the editable host and port; only the password result exposes the exact
normalized username and exact password. Those credentials are immutable
plaintext strings and cannot be zeroed. Keep the result only in private memory
while the active secure edit surface needs it, then drop every reference; never
serialize it, place it in saved state, log it, or use it as a diagnostic.

Anonymous profiles do not use the Android Keystore. Password fields remain
encrypted at rest with endpoint-bound associated data. The deprecated
`TvheadendCredentialStore` remains binary compatible for existing applications
and shares the same atomic record.

The default build resolves
`at.bernhardberger.tvheadend:htsp:0.7.0` from Maven Central. Maintainers working
across adjacent checkouts may explicitly opt into source substitution with
`-Ptvheadend.htsp.composite=true`; CI and release builds do not use that
property.

## Live stream profiles

`TvheadendSession.getStreamProfiles(currentSession)` discovers the ordered stream
profiles for exactly the generation represented by that observation. Pass a
returned ID through `LivePlaybackOptions` to select that server profile for one
live target:

```kotlin
val observed = session.observation.value
val currentSession = requireNotNull(observed.currentSession)
val channel = requireNotNull(observed.channel(channelId))
val selected = when (val result = session.getStreamProfiles(currentSession)) {
    is StreamProfilesResult.Available -> result.profiles.firstOrNull()
    else -> null
}

if (selected != null) {
    when (val target = session.bindLivePlayback(currentSession, channel.id)) {
        is PlaybackBindingResult.Bound -> handlePlaybackTargetResult(
            coordinator.setLiveTarget(
                target.binding,
                LivePlaybackOptions(
                    streamProfileId = selected.id,
                    timeshiftPeriod = 30.minutes,
                ),
            ),
        )
        PlaybackBindingResult.ObservationExpired,
        PlaybackBindingResult.TargetUnavailable,
        -> Unit
    }
}
```

The listed name and comment are presentation metadata. Profile IDs are opaque
and redacted from string rendering. A canonical ID does not require a prior
discovery-cache lookup; the exact admitted generation validates selection.
Omitting the profile keeps TVHeadend's default selection.

## Atomic session observation

`TvheadendSession.observation` is the single atomic `StateFlow` for lifecycle,
channel, EPG, DVR, DVR configuration and disk-space freshness, server
capabilities, and recording-progress capability. Read related values and call
selectors from one captured observation rather than collecting independent
flows:

```kotlin
val observed = session.observation.value
val channel = requireNotNull(observed.channel(channelId))
val current = observed.eventAt(channelId, now)
val next = observed.nextEvent(channelId, now)
```

`currentSession` is an opaque proof that the lifecycle and primary channel,
EPG, and DVR snapshots are current for one session-owned generation. It becomes
`null` in the same publication that retires that generation. Retained stale
snapshots remain selectable from that retired observation without being
mistaken for current data.

`session.isCurrent(currentSession)` inspects currency without side effects;
`session.awaitCurrentSession(replaced = currentSession)` waits for a distinct
proof and propagates cancellation. Neither leases the returned proof. Its
opaque, process-local `generationIdentity` scopes caches across metadata-only
republication under the same connection authority, but cannot reconstruct or
authorize a proof. Successful EPG searches and stream-profile discovery expose
their exact `originatingSession` for provenance, which may already be retired
when the result is observed.

Retained snapshots remain selectable during `SessionState.Synchronizing`, but
playback binding waits for a new authoritative `currentSession` after
`SessionState.Ready`. An old observation can therefore never select a colliding
target in a replacement connection generation. DVR mutations and recording
progress are also gated on `SessionState.Ready`.

`Ready` follows the server's authoritative initial metadata fence. EPG coverage
queries and DVR configuration and disk-space enrichment continue as supervised
generation-owned background work and do not delay or tear down readiness.
Consumers can acquire one bounded EPG horizon for the captured generation:

```kotlin
val currentSession = requireNotNull(observed.currentSession)
val result = session.epgRepository.acquireCoverage(currentSession, channel.id, programme.stop)
```

`EpgCoverageAcquisitionResult` settles as covered-with-data, covered-empty,
ineligible, or observation-expired. Covered results retain the exact immutable
observation that established the coverage.

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
        addTvheadendArtwork()
    }
    .build()

val observed = session.observation.value
val currentSession = requireNotNull(observed.currentSession)
val channel = requireNotNull(observed.channel(channelId))
val artwork = TvheadendArtwork.create(session, currentSession, channel.icon)
```

The model rejects external URLs and malformed selectors. The SDK installs an
opaque process-local memory key scoped to the current connection generation;
selectors and connection details do not enter the key. The streamed result has
no disk identity, so this component does not authorize persistent authenticated
artwork caching. Coil owns decoding and closes every successfully returned
image source. `TvheadendArtworkLoadException.failure` preserves typed failures
such as `ACCESS_DENIED` without parsing exception text.

## Recording progress

`SessionObservation.recordingProgressCapability` is `SUPPORTED` only when the
current ready generation can close recording files without changing play count
and can report position and watched state separately. Unknown and pre-v27
connections disable resume and progress reporting. Completed files remain
playable from the beginning without those optional operations.

`DvrProgressPolicy` offers every positive saved position for completed
recordings. Its pure tracker uses one 30-second elapsed cadence and reports any
positive movement, plus explicit pause and terminal observations. Natural end
marks a completed recording watched; an orderly exit requires at least 95% of a
known positive actual media duration. Errors and growing recordings never infer
completion.

Playback progress and cutpoints are owned by an observation-bound
`PlaybackBinding.Recording`, not the mutation-only `DvrRepository`. The Media3
coordinator serializes reports, preserves terminal ordering, and never resolves
a later connection generation for an installed target.

## Media3 playback coordination

`sdk-media3` provides a narrow coordinator for live channels, completed
recordings, and the bounded growing pass-through MPEG-TS path. The application
owns the `ExoPlayer` and the coroutine running the coordinator. Source changes,
resume registration, recovery, and progress observation are serialized on the
player's application looper; the coordinator
does not create or release the player or own a service, MediaSession, audio
focus, notification, surface, autoplay, navigation, or presentation policy.

```kotlin
val coordinator = createTvheadendPlaybackCoordinator(player)
val owner = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
    coordinator.run()
}
val observed = session.observation.value
val currentSession = requireNotNull(observed.currentSession)
val channel = requireNotNull(observed.channel(channelId))
val completedRecording = requireNotNull(observed.dvrEntry(completedRecordingId))
val activeRecording = requireNotNull(observed.dvrEntry(activeRecordingId))

val liveTarget = session.bindLivePlayback(currentSession, channel.id)
if (liveTarget is PlaybackBindingResult.Bound) {
    handlePlaybackTargetResult(
        coordinator.setLiveTarget(
            liveTarget.binding,
            LivePlaybackOptions(timeshiftPeriod = 30.minutes),
        ),
    )
}
handleTimeshiftCommandResult(coordinator.seekTimeshift((-30).seconds))
handleTimeshiftCommandResult(coordinator.returnToLive()) // bounded near-live position
handleTimeshiftCommandResult(coordinator.pauseTimeshift()) // pauses server delivery only
handleTimeshiftCommandResult(coordinator.resumeTimeshift())

val completedTarget = session.bindRecordingPlayback(currentSession, completedRecording.id)
if (completedTarget is PlaybackBindingResult.Bound) {
    handlePlaybackTargetResult(
        coordinator.setRecordingTarget(completedTarget.binding, RecordingPlaybackStart.RESUME),
    )
}
val growingTarget = session.bindRecordingPlayback(currentSession, activeRecording.id)
if (growingTarget is PlaybackBindingResult.Bound) {
    handlePlaybackTargetResult(
        coordinator.setRecordingTarget(growingTarget.binding, RecordingPlaybackStart.START_OVER),
    )
}

handlePlaybackShutdownResult(coordinator.shutdown(2.seconds))
owner.join()
session.shutdown()
player.release()
```

`CoroutineStart.UNDISPATCHED` lets `run()` claim the coordinator lifecycle before
the first command can be submitted. Applications should handle every typed
target, timeshift, and shutdown result rather than assuming the player changed.
Target and timeshift outcomes expose stable dispositions, categories, and direct
predicates; exact SDK-owned values are non-exhaustive so consumers must retain a
fallback. A timeshift `UNCONFIRMED` disposition means acceptance could not be
proved, while an accepted command may still carry a terminal category when its
result cannot be used safely.

`TvheadendPlaybackCoordinator.timeshiftState` reports only the current live
target's positive server grant and ordered server observations. Buffered
duration, position behind live, and server pause state remain `null` until valid
status events arrive. Timeshift pause and resume send server speeds `0` and
`100`; ordinary Media3 play/pause remains application-owned.

`TvheadendPlaybackCoordinator.subscriptionIssue` reports only the current live
target's canonical TVHeadend issue. Known server codes map to safe non-exhaustive
`SubscriptionIssue` values; unknown or localized values become `UNKNOWN`, and raw
server text is never exposed. The exact no-input status maps to `NO_INPUT`
unless a conflicting known canonical error is present. The state clears on
period retry, target replacement, recording playback, stop, and shutdown.

`TvheadendPlaybackCoordinator.liveDiagnostics` conditionally reports immutable
source display metadata, frontend state and
measurements, and server queue depth, media span, and B/P/I drops for that same
current live target. Relative values are
percentages, absolute SNR is dB, absolute signal is dBm, BER is explicitly raw,
and unavailable observations are `null`. Display text is normalized and bounded;
controls and recognizable UUID, address, locator/userinfo, MAC, authorization,
and credential-assignment forms are omitted. It does not infer secrets from
otherwise ordinary human-assigned names. Raw server text and logs are not
exposed. Recording replacement and every live subscription or
coordinator termination clear the state; stale generations cannot republish it.

Direct live playback feeds TVHeadend's packet-level `AAC` stream to Media3's
maintained `AdtsReader`. TVHeadend normalizes AAC-LATM packets to ADTS before
HTSP delivery, so the SDK does not infer the source transport framing or expose
a separate LATM codec type. This path has deterministic packet-fixture coverage;
the current acceptance server has no AAC service, so it is not a live-server AAC
claim.

Completed recordings remain playable when recording progress is unknown or
unsupported; the coordinator starts them from the beginning and disables resume
and reporting. Supported progress enables normal completed-recording resume and
reporting. An active target still requires supported progress, one stable `.ts`
file, and explicit `START_OVER`. `RESUME` returns
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
