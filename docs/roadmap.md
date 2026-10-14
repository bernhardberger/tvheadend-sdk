# Roadmap to 1.0

This roadmap is based on a gap review of `0.24.0` (2026-10-11). It records the
direction toward a stable `1.0` and the decisions already taken. It is a plan,
not a release commitment: versions, scope, and order can change, and every
release still follows [versioning](versioning.md) and [releasing](releasing.md).

## Where 0.24.0 stands

The SDK already covers what a TV client needs:

- `sdk-core` covers nearly all of HTSP. That includes channels and tags, EPG
  with search, series links, ratings and genres, and DVR
  create/update/stop/cancel/delete. It also has autorec and timerec rules,
  multiple DVR configurations, disk space, cutpoints, stream profiles, and
  access rights.
- Playback supports:
  - **Video:** MPEG-2, H.264 and HEVC.
  - **Audio:** MP2, AC-3, E-AC-3 and AAC, plus passthrough.
  - **Subtitles:** DVB bitmap, and live text from teletext pages the server
    has extracted.
  - **Live:** timeshift with selection-based seeking, signal diagnostics, and
    tuner priority.
  - **Recordings:** completed recordings with resume, and growing `.ts`
    recordings.
- The player consumes the SDK without HTSP imports, infrastructure opt-ins in
  production code, or reflection.

The main gap before 1.0 is how stable the API is, not missing features. 0.24.0
had about eleven breaking changes. They are concentrated in the Media3
coordinator.

## Decisions

### Compatibility

1. **What 1.x promises.** Source and behavioural compatibility for stable
   public API. The checked-in ABI dumps help review by showing every public API
   change, but they are not a contract. `sdk-media3` and `sdk-android` have no
   dumps until maintained tooling supports AGP's built-in Kotlin (KT-83410).
   That gap is accepted for 1.0.
2. **Experimental tier.** A `@RequiresOptIn` experimental annotation marks API
   that the 1.x promise does not cover. New features driven by the player land
   there first. Once proven in the player they become stable in a later minor.
   At 1.0, anything still moving ships as experimental, not frozen.
3. **Additive evolution.** Public API is shaped so that likely future needs
   are additive:
   - new data-class properties appended with defaults, so existing Kotlin
     calls keep compiling;
   - result types documented as open, so consumers keep a fallback branch;
   - typed values rather than raw server encodings.

### Media3

4. **`sdk-media3` follows Media3.** `sdk-media3` is the Media3 adapter, and
   its public API may expose Media3 types, including `@UnstableApi` ones such
   as `RenderersFactory` and `LoadControl`. When a Media3 upgrade changes such
   a type, the matching `sdk-media3` signatures may change in an SDK minor,
   marked `BREAKING` in the changelog. Changes to `sdk-media3` that a Media3
   upgrade does not require follow the 1.x promise. `sdk-core`,
   `sdk-playback`, and `sdk-android` get the full 1.x promise. Decided on
   2026-10-12 instead of hiding Media3 behind SDK-owned types: the player is
   the only consumer and uses Media3 `@UnstableApi` itself, and an SDK facade
   over Media3 would grow with every customisation the app needs.
5. **One Media3 version per SDK release.** Each SDK release names the Media3
   version it was built and tested with, and applications use that version.
   SDK internals rely on Media3 internals, so a newer Media3 can fail at
   runtime. Media3 upgrades ship in SDK minors.
6. **No repository split.** `sdk-media3` stays in this repository, and all
   five modules share one version.

### HTSP

7. **HTSP stays a separate repository.** The split keeps protocol coupling
   visible and keeps a clean-room, Kotlin Multiplatform, or Rust
   reimplementation possible behind `ProtocolGateway`.
8. **HTSP and SDK versions are independent.** HTSP is an `implementation`
   dependency and no HTSP type appears in SDK signatures. An HTSP major
   therefore normally means an SDK minor. Only a change to SDK public API
   requires an SDK major.
9. **Release train.** HTSP, SDK, and player release candidates prove each
   other. HTSP 1.0 is a prerequisite for SDK 1.0, and the three finals are
   expected to ship close together. Player 1.0 is a promise to users and may
   ship on an SDK release candidate if the SDK readiness criteria are not yet
   met.

### Platforms

10. **Product phases.** Android TV first. Then mobile and tablet touch, plus
    controller support. Then Linux appliances (Raspberry Pi, x86, SteamOS).
    These are product phases, not SDK majors. The SDK owns no UI, so touch,
    controller, and form-factor work happens in the app.
11. **Linux playback.** Linux playback, when it comes, is a new adapter
    module beside `sdk-media3`, built on `sdk-playback`. That makes it an
    additive SDK minor. Whether to use Kotlin Multiplatform or native binaries
    is decided after Android. Public `sdk-core` API avoids JVM-only types
    where that is cheap. `java.time` is accepted, because its stability
    outweighs a cheaper Kotlin Multiplatform migration.

### Scope

12. **Growing recordings stay MPEG-TS only for 1.0.** Matroska and MP4
    recordings that are still being written keep the typed
    `GROWING_RECORDING_DEFERRED` outcome. Each container would need its own
    feasibility work (see the
    [growing-recording evaluation](growing-recording-evaluation.md)), a seek
    wrapper on Media3 internals, and device
    acceptance. The decision applies to the SDK and the player alike, and a
    1.x minor can add support later.
13. **Raw teletext is out for 1.0.** Live subtitles already cover every
    teletext subtitle page the channel announces, because TVHeadend extracts
    them as text subtitle streams. Recordings with only teletext subtitles
    show none: Media3 has no teletext decoder, and the owner does not use
    subtitles in recordings. A 1.x minor can add a decoder later.

## 1.0 readiness criteria

SDK 1.0 is released when its criteria are met, not by date:

- The player has no workarounds for missing SDK capability.
- Two consecutive minors make no structural breaking change to the playback
  coordinator.
- Every stable public declaration is used by the player or marked
  experimental.
- The 1.x compatibility policy and the server compatibility matrix are
  written.
- A fresh real-server device run covers the playback matrix below.
- HTSP has at least a release candidate, and SDK 1.0 pins HTSP 1.0 final.

## Player coordination

The player's own 1.0 roadmap lives in the player repository. These points
connect the two:

- SDK 0.26 lands before player 0.4 starts series rules, timer rules, and
  entry editing. Those features use exactly the DVR request types 0.26
  reshapes. Player 0.3 work does not depend on 0.26.
- The player's series-recording brief and the 0.26 design note are written
  together, so the typed rule options match what the player exposes.
- The player's physical-TV reliability session also counts as SDK device
  evidence when it records the SDK version and what it covered.
- R8 verification (0.27) precedes the player's minified release build.
- Growing recordings stay MPEG-TS only for both the SDK and the player
  (decision 12).

## Milestones

Milestones are named by theme. The version numbers are tentative and shift
when other work ships first.

### 0.25: benchmarks and performance (released)

0.25.0 added a benchmark suite and reduced SDK allocations without changing
public API.

### 0.26: API shape and cleanup (released)

0.26.0 was released on 2026-10-12. This is a breaking release. Its changes are grouped so that later releases can
stay additive.

- Introduce the experimental opt-in annotation. Mark areas still in motion,
  such as the growing-recording resume observation.
- Convert open enums and data-carrying sealed results into open sets, so new
  values do not break exhaustive consumers. Public data classes stay; under
  the source-compatibility promise, new properties are appended with
  defaults. See the [0.26 design note](api-shape-0.26.md).
- Unify the near-duplicate failure taxonomies of the DVR mutation, progress,
  cutpoint, stream-profile, and EPG search results around one shared failure
  value.
- Decide, per enum and sealed result, whether it is open or closed. Document
  the fallback for open ones.
- Type raw server encodings:
  - weekday masks become `Set<DayOfWeek>`;
  - minutes since midnight become `LocalTime`;
  - extra minutes become `Duration`;
  - `DvrEntry.duplicate` becomes `Boolean`;
  - image fields become `ArtworkId`;
  - duplicate detection, broadcast type, and priority become enums.
- Make server internals non-public: `DvrEntry.path`, `DvrRecordingFile.path`,
  `owner`, and `creator`.
- Move test helpers out of production API, or behind the testing opt-in:
  - `TimeshiftTestFixture` in `sdk-media3`;
  - `SessionGenerationTestAuthority` and `TvheadendTestResultFactory` in
    `sdk-core`.
- Remove leftovers:
  - the deprecated `TvheadendCredentialStore` and its legacy storage path;
  - `PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED`;
  - the `GrowingStartOverOnly` name.
- Resolve the process-wide session factory ignoring policies when an owner
  already exists.

### 0.27: consumer gaps (released)

0.27.0 was released on 2026-10-12. Every new API ships behind the
experimental opt-in; see the [0.27 design note](consumer-gaps-0.27.md).

- Move the player's copied playback policies into the SDK, then delete the
  app copies:
  - recover a recording at a position, replacing the app's copy of the
    growing-resume rule and its constants;
  - an optional bounded live retune budget;
  - a live-start buffer parameter on `createTvheadendLoadControl` that knows
    about timeshift skips.
- Mark a recording watched or unwatched, and reset resume, outside playback.
- Report when metadata was last refreshed, so the app can show an honest
  stale or empty guide.
- Recording stream information (resolution, codecs, languages) from the
  recording file metadata HTSP already parses.
- A typed radio versus TV channel classification.
- Verify the SDK under R8: a minified build in the consumer-contract check,
  and consumer keep rules where the SDK needs them.
- Document whether more than one coordinator per session is supported.
  Android TV disallows media picture-in-picture, so this is not needed for
  the Android TV 1.0.

### Next 0.x minors: player-driven gaps

The owner decided on 2026-10-12 not to start the 1.0 track yet. 0.x minors
continue to close gaps the player finds, with new API behind the experimental
opt-in.

- Gaps from the player as it approaches its release candidate.
- Programme credits (cast and crew) ship in 0.28 as experimental EPG metadata.
- Gaps from an optional mobile proof-of-concept app. Mobile is likely to test
  what Android TV does not: picture-in-picture (a second coordinator per
  session), background playback from a media service with deep sleep, and
  network changes between Wi-Fi and mobile data.
- Remove the `createTvheadendLoadControl(): LoadControl` overload that the
  0.27 `TvheadendLoadControl` replaces.
- Keep the Media3-derived `androidx.media3.extractor.ts` classes as internal
  implementation, and re-sync them on Media3 upgrades.

### 1.0 groundwork (parked)

Starts when the player is close to its release candidate and, if it is built,
the mobile proof of concept has shown no major SDK gaps.

- Review and finish the
  [draft 1.x compatibility policy](compatibility-policy.md) and answer its
  open questions. It covers:
  - the compatibility scope, and the status of the opt-in tiers;
  - the `sdk-media3` tier (decision 4) and the Media3 version per release;
  - the deprecation cycle;
  - the minSdk, compileSdk, JDK and Kotlin rules;
  - the TVHeadend and HTSP server compatibility matrix.
- Document the `sdk-media3` tier in the module KDoc and README.
- Mark every breaking change consistently as `BREAKING` in the changelog.
- Add KDoc to public properties and enum entries.

### 1.0.0 release candidates

- At least two minors without a structural coordinator break (see the
  readiness criteria).
- Run a fresh real-server device acceptance across:
  - video: live H.264 and HEVC;
  - audio: MP2, AC-3/E-AC-3 and AAC;
  - subtitles: DVB and text;
  - radio and passthrough switching;
  - live video restart;
  - completed and growing recordings.
- Update the release tooling, which currently rejects any 1.x version. This
  needs separate maintainer approval.
- Coordinate the HTSP, SDK, and player release candidates and finals.

## After 1.0

Deferred by owner decision on 2026-10-12: the series-rule upcoming-recordings
helper has no consumer after Player dropped series-rule UX D2–D6 on 2026-10-11.
Rules stay in the TVHeadend web UI. Reconsider only if a consumer returns; see
[item 8's retained wire findings](consumer-gaps-0.27.md#8-series-rule-upcoming-recordings--deferred).

These additions should fit 1.x minors if the API shape milestone lands as
planned:

- single-event EPG lookup;
- stream tickets for casting or external players;
- CEA-608/708 captions;
- descrambling diagnostics;
- trick-play speeds;
- start-over anchored to the EPG;
- multiple saved server profiles, with exactly one still active;
- a Linux playback adapter module.

## Open questions

- If a clean-room HTSP rewrite is still wanted, what drives it? Relicensing
  only needs a rewrite if the current code derives from third-party GPL code.
