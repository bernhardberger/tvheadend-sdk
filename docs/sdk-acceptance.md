# SDK acceptance

Phase 6 SDK acceptance was run on 2026-08-25 against TVHeadend
`4.3-2735~gfcd987f0b` using the G10 Smart TV Pro on Android API 31. The run used
one physical device. The independent-process checks below do not claim
verification on two physical devices.

## Session and repository milestones

The first acceptance process observed these raw elapsed times from the cold
`connect` call. They are observations, not release thresholds:

| Milestone | Elapsed |
| --- | ---: |
| `Synchronizing` | 352 ms |
| First channel repository content | 2,008 ms |
| `Ready` after authentication and initial synchronization | 2,028 ms |
| First live target admitted | 2,330 ms |
| First rendered video frame | 5,378 ms |

The initial current repositories contained 115 channels, 12 tags, 3,745 EPG
events, and 444 DVR entries. A public six-hour EPG coverage request returned
`SATISFIED`; the synchronized coverage already reached the requested horizon,
so zero coverages reported a separate `queriedTo` value during this observation.
This is public repository evidence, not an inferred count of internal requests.

The same session disconnected explicitly, exposed its channel catalog as
stale, and reconnected with the same normalized profile. It entered
`Synchronizing` after 115 ms, admitted a known retained channel and rendered it
before readiness, then reached `Ready` after 2,866 ms. Cold live playback,
replacement with a distinct live channel, reconnect playback, decoder output,
and orderly coordinator teardown all completed.

Raw memory observations were:

| Process point | Java heap | Native heap | Total PSS |
| --- | ---: | ---: | ---: |
| Stage one, before cold connect | 3,486,440 bytes | 4,174,032 bytes | 40,077 KiB |
| Stage one, after reconnect playback | 30,615,880 bytes | 47,878,512 bytes | 74,685 KiB |
| Stage three, after final teardown | 10,630,800 bytes | 4,051,072 bytes | 54,028 KiB |

The final row is from a later independent process and is not a same-process
delta. No retained-memory threshold or leak claim is derived from these three
observations.

## Recording progress and process loss

Stage one scheduled one marker-named disposable recording, captured 75 seconds,
issued a stream-confirmed stop, and required authoritative `COMPLETED` state
with positive media bytes. No existing recording was mutated.

Stage two ran in a process distinct from stage one. The SDK reported semantic
`RecordingProgressCapability.SUPPORTED`, which requires the complete HTSP v27+
recording-progress contract and has no older-protocol fallback. Production
Media3 playback started the completed recording from zero. The server accepted
a periodic checkpoint at 28,000 ms, playback continued to 38,052 ms without a
pause or terminal event, and the process flushed
`checkpoint-written-about-to-kill` before killing itself. The instrumentation
runner reported `Process crashed.` as expected; that loss is process-boundary
evidence rather than a passing JUnit result.

Stage three ran in another process. It observed exactly 28,000 ms from the
server, rather than the later unreported position, and `RESUME` began from that
positive checkpoint. An explicit pause at 33,062 ms updated progress without
marking the recording watched. An orderly exit below 95 percent remained
unwatched, natural Media3 end advanced play count to one, and a 96 percent
orderly exit advanced it to two. The coordinator drained, the session and
player shut down, and the package-owned DVR entry and app-private handoff state
were removed.

Each process consumed a separately provisioned mode-0600 app-private one-use
profile before connecting. The handoff identifies the fixture by ID, stable
UUID, and unique marker before any cross-process playback or mutation. The
profile, handoff state, and disposable DVR fixture were absent after cleanup.
Status output retained no endpoint,
credential, channel ID, recording ID, path, or subscription ID.

## Playback regressions

The exact existing
`PlaybackDeviceVerificationInstrumentationTest#timeshift_seek_return_to_live_and_completed_recording_resume_on_device`
method passed against the same device and server. It rendered live H.264 with
audio, accepted rewind and return-live seeks, observed two timestamp anchors,
and rendered a completed recording from a positive resume point. The exact
`PlaybackCoordinatorLooperInstrumentationTest#everyTargetAndRetirementOperationRunsOnTheApplicationLooper`
method also passed, proving target and retirement operations remained on the
player application looper.

## Staged consumer

The standalone `consumer-contract` build is intentionally excluded from the
root settings, so the repository still contains exactly five SDK modules. It
compiles a normal Android consumer with strict direct dependencies on the staged
`sdk-media3` and `sdk-android` coordinates. The source exercises the atomic
server-profile API as well as playback coordination. Its check requires the
expected Android, core, Media3, playback, and HTSP runtime graph, no project
substitution, no raw HTSP or infrastructure opt-in in consumer source, byte
identity with staged artifacts, and matching SHA-256 sidecars.

## Reproduction protocol

1. Install the `sdk-media3` Android test APK and wake the device.
   Set `ANDROID_HOME` or provide the ordinary ignored `local.properties` SDK
   path before running the standalone consumer build from a fresh checkout.
2. Run `provision-p4-5-profile.sh`, then invoke
   `SdkAcceptanceInstrumentationTest#stage_one_cold_reconnect_and_disposable_recording`.
3. Without clearing package data or reinstalling, provision a fresh profile and
   invoke `SdkAcceptanceInstrumentationTest#stage_two_checkpoint_then_abrupt_process_loss`.
   Require the fixed pre-kill marker and expected runner process-crash result.
4. Provision a fresh profile and invoke
   `SdkAcceptanceInstrumentationTest#stage_three_cross_process_resume_completion_and_cleanup`.
5. Provision a fresh profile and invoke the exact real-server playback
   regression above, then invoke the exact coordinator-looper regression.
6. Require zero skips and verify the one-use profile, acceptance state, and
   disposable DVR entry are absent. A skipped stage is failed provisioning, not
   acceptance evidence.

If a provisioned stage fails after scheduling, preserve the app-private handoff
instead of clearing package data. After reprovisioning, invoke
`SdkAcceptanceInstrumentationTest#cleanup_owned_fixture_after_failed_acceptance`;
it deletes only an authoritative ID, UUID, and marker match, or accepts
authoritative absence, before removing the recovery state.
