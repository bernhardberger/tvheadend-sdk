# SDK 1.x compatibility policy

Draft, parked and not yet reviewed; see the
[roadmap](roadmap.md#10-groundwork-parked). Takes effect with 1.0.0; until then
the provisional 0.x policy in [versioning](versioning.md) applies.

This implements [roadmap decisions 1–13](roadmap.md#decisions). The version pins
and declaration inventory below describe the current source, not a released 1.0.
All five SDK modules share one version; consumers use matching versions.
Release checks remain in [releasing](releasing.md).

## Compatibility scope

Stable public API has Kotlin source and behavioural compatibility throughout
1.x, subject to the Media3 and platform/toolchain rules below. Stable means a
public declaration without an SDK opt-in requirement or an internal-use
restriction. This includes stable application-facing fakes in `sdk-testing`.
The promise covers documented valid use, including implementations of public
interfaces intended for application fakes.

Existing positional and named calls, direct `copy(...)` calls, property access,
and destructuring of existing data-class positions keep compiling. Parameter
names and defaults are part of that source contract. New data-class properties
are appended with defaults. They participate in `equals` and `hashCode`; this
specific behavioural extension must be documented in the release notes.
See `docs/api-shape-0.26.md:19-39`.

Documented outcomes, cancellation propagation, ownership, lifecycle and
generation validity remain compatible. A fix may restore documented behaviour;
calling an intentional contract change a bug fix does not make it compatible.

The promise excludes:

- Binary linkage without recompilation, reflection over generated members,
  and compiler-generated JVM signatures. Recompile consumers when upgrading.
- Callable references to data-class constructors and `copy`, whose arity can
  change. Java source callers are outside the Kotlin promise, including
  data-class constructor calls without `@JvmOverloads`. These are the exclusions
  required by `docs/api-shape-0.26.md:35-39`; ordinary Kotlin calls remain covered.
- `internal`, private, internal-package and `@RestrictTo` implementation API,
  even if bytecode visibility or reflection makes it reachable.
- A fixed membership for documented open result/value sets. New values may
  appear in minors; consumers retain a fallback. Existing values keep their
  documented meaning. Adding cases to a stable closed enum or sealed type is
  a major change when it breaks exhaustive callers.
- Exact `toString()`, log and diagnostic wording or formatting. Typed outcomes
  are the contract; redaction requirements still apply.
- Undocumented timing, thread scheduling, emission counts, throughput and
  latency. Documented ordering, thread affinity and explicit timeout semantics
  remain covered. Acceptance measurements are not timing guarantees.

ABI dumps are a review aid, not a binary compatibility contract. The three JVM
modules have dumps. The accepted KT-83410 gap leaves `sdk-media3` and
`sdk-android` without dumps until maintained tooling supports AGP's built-in
Kotlin. Explicit API compilation and other checks remain required
(`docs/build-matrix.md:36-55`; `docs/roadmap.md:37-41`).

## Opt-in tiers

The repository defines four `@RequiresOptIn` markers. All require explicit
opt-in at `ERROR` level. None grants the stable 1.x promise to annotated API.
Signatures, behaviour and availability in these tiers may change in a minor,
including removal. Patches remain backward-compatible, including these tiers.
Every incompatible change gets a `BREAKING` entry and migration guidance.

| Marker | Intended consumer and current status | Declaration |
|---|---|---|
| `ExperimentalTvheadendApi` | Application developers evaluating new SDK features. Provisional production API. | `sdk-playback/src/main/kotlin/at/bernhardberger/tvheadend/sdk/playback/ExperimentalTvheadendApi.kt:3-16` |
| `SubscriptionInfrastructureApi` | Transport-neutral playback infrastructure and adapter composition, not ordinary application playback control. | `sdk-playback/src/main/kotlin/at/bernhardberger/tvheadend/sdk/playback/SubscriptionConnection.kt:7-20` |
| `TvheadendTestingApi` | Fake-backed tests constructing session authority and playback results. Test-only API. | `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendTesting.kt:24-31` |
| `FakePlaybackApi` | Playback integration tests using fake playback authority bindings. Test-only API. | `sdk-testing/src/main/kotlin/at/bernhardberger/tvheadend/sdk/testing/FakeTvheadendSession.kt:60-67` |

New player-driven features start experimental. After use in the player proves
the shape and behaviour, a later minor may remove the opt-in requirement and
announce promotion. Compatibility starts with that stable shape. API still
moving at 1.0 stays experimental (`docs/roadmap.md:42-45`). Infrastructure and
testing tiers have no automatic graduation date. Promotion requires an explicit
decision about the supported consumer contract, removal of all applicable SDK
opt-ins, and release notes. Stability does not imply suitability for production
use when the declaration remains documented as a test helper.

Adding a restrictive opt-in to already stable API is a major change. Media3's
own `@UnstableApi` is a separate upstream marker, not a fifth SDK-defined tier.

## Media3 boundary

`sdk-media3` follows Media3. Its public API may expose Media3 types, including
`@UnstableApi` types. When a Media3 upgrade changes such a type, the matching SDK
signature may change in an SDK minor, marked `BREAKING`. Changes not required
by that upgrade follow the normal 1.x promise. An internal Media3 dependency
alone is not permission to redesign stable SDK-owned API in a minor.
`sdk-core`, `sdk-playback` and `sdk-android` receive the full stable API promise.
See roadmap decision 4 (`docs/roadmap.md:55-64`).

Each SDK release names exactly one Media3 version, built and tested together.
Applications must use that version for their Media3 dependencies. Do not assume
that a newer version is runtime-compatible. Every Media3 upgrade, including an
upstream patch upgrade, ships in an SDK minor. The current pin is **1.11.1**
(`gradle/libs.versions.toml:20`). Re-sync the internal Media3-derived extractor
code during upgrades (`docs/roadmap.md:65-68`, `docs/roadmap.md:223-224`).

### Current public Media3 signatures

These declarations directly expose types marked `@UnstableApi` in Media3 1.11.1:

| Declaration | Exposed unstable type | Source |
|---|---|---|
| `createTvheadendRenderersFactory(context, audioOutputProvider)` | Return type `RenderersFactory` | `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendDecoderPolicy.kt:26-30` |
| `createTvheadendLoadControl()` | Return type `LoadControl` | `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendLoadControl.kt:27-28` |

The no-argument factory is scheduled for removal in a coming 0.x minor
(see the [roadmap](roadmap.md#next-0x-minors-player-driven-gaps)); update this inventory then. Its replacement takes
`TvheadendLiveStartBuffer` and returns experimental `TvheadendLoadControl`.

Other public Media3-facing signatures follow. Using unstable members internally
does not mean every type in a declaration's signature is itself unstable:

- `createTvheadendPlaybackCoordinator` accepts `ExoPlayer`
  (`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackCoordinator.kt:959-967`).
- Its experimental load-control overload also accepts `ExoPlayer`; the
  experimental handle's `configure` accepts and returns `ExoPlayer.Builder`
  (`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendLoadControlConfiguration.kt:66-104`).
- `createTvheadendPlaybackRecovery` accepts `Player`
  (`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendPlaybackRecovery.kt:216-222`).
- `TvheadendAudioOutputProvider` extends `ForwardingAudioOutputProvider`;
  `setPassthroughEnabled` accepts `ExoPlayer`; its public `getFormatSupport`
  override accepts `AudioOutputProvider.FormatConfig` and returns
  `AudioOutputProvider.FormatSupport`
  (`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendAudioOutputProvider.kt:31-35`,
  `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendAudioOutputProvider.kt:54`,
  `sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendAudioOutputProvider.kt:118`).
- `MediaItem.isTvheadendLive()` exposes `MediaItem` as its receiver
  (`sdk-media3/src/main/kotlin/at/bernhardberger/tvheadend/sdk/media3/TvheadendMediaItems.kt:15`).

## Deprecation cycle

1. Introduce a replacement and a migration example. A minor may add
   `@Deprecated(level = WARNING)` to stable API and name the intended major
   removal release. Give consumers at least one released minor with the warning.
2. Keep the old API callable and behaviourally compatible for the rest of 1.x.
   Minors may improve warning text and `ReplaceWith`, or relax a deprecation.
3. Removal, hiding with `HIDDEN`, and escalation to `ERROR` require a major.
   `ERROR` breaks compilation even if bytecode remains. Deprecation age does not
   permit removal in a minor. A consumer's warnings-as-errors setting may require
   migration or suppression when a new warning is introduced.

The experimental and Media3-forced exceptions above may use a shorter migration
cycle, but must state the reason and replacement in their `BREAKING` entry.

## Version numbers

| Release | Allowed changes |
|---|---|
| Major | Incompatible stable source or behavioural changes; stable removals; raising the JVM runtime/bytecode floor or the supported server protocol floor. |
| Minor | Additive API and features; experimental promotions or breaks; Media3 upgrades and their required signature changes; announced platform/toolchain changes below. |
| Patch | Backward-compatible fixes and documentation. No known breaking change, new requirement floor, experimental break or Media3 upgrade. |

An HTSP dependency major normally requires only an SDK minor. HTSP is an
implementation dependency and its types do not appear in SDK public signatures.
If the upgrade breaks the SDK's stable public contract, it requires an SDK major
(`docs/roadmap.md:77-80`). The current dependency is **1.0.0-beta.1**; SDK 1.0 must
pin HTSP **1.0 final** (`gradle/libs.versions.toml:13`; `docs/roadmap.md:128`).

Mark every known incompatibility `BREAKING`, including permitted minor changes
and excluded call forms affected by an API change. State the affected tier,
reason, old/new requirements and migration. A changelog label does not authorize
a break outside this policy. Keep published bytes immutable.

## Platforms and toolchains

The API promise applies on the release's documented platform and toolchain
baseline. This draft follows AndroidX practice of publishing evolving Android
requirements separately from API stability, and Kotlin's distinction between
the library build compiler and consumer metadata compatibility. The specific
release rules here are SDK policy, not a claim that AndroidX uses identical rules.
See [AndroidX minimum SDK policy](https://developer.android.com/jetpack/androidx/versions#minimum-sdk-version)
and [Kotlin binary evolution](https://kotlinlang.org/docs/kotlin-evolution-principles.html#evolving-the-binary-format).

| Requirement | Current baseline | When it may rise |
|---|---|---|
| Android runtime `minSdk` | 28 | Minor, with notice in a preceding minor, rationale and `BREAKING` migration note naming the last supporting release. Never a patch. |
| Android `compileSdk` | 37 | Minor. Publish any new consumer minimum compile SDK; this does not by itself raise runtime `minSdk`. |
| JVM target / class-file major | Java 17 / 61 | Major when the runtime or bytecode floor rises. |
| SDK build JDK / toolchain | 21 | Minor; keep the JVM target independent. A build JDK increase alone is not a runtime requirement increase. |
| Kotlin compiler | 2.4.10 | Minor for a new language/metadata line or higher consumer minimum. A compiler patch may ship in an SDK patch only with unchanged requirements and compatible output. |
| SDK build AGP | 9.4.1, built-in Kotlin | Minor for a new AGP line or consumer minimum. A compatible AGP patch may ship in an SDK patch if requirements stay unchanged. |

Pins and bytecode checks are recorded in `gradle/libs.versions.toml:2-24`,
`build.gradle.kts:131-160` and `docs/build-matrix.md:7-15`. The SDK build pins are
not evidence that every consumer needs exactly that AGP or build JDK. Consumers
must satisfy the published artifacts' metadata, their AGP/Gradle requirements
and dependency requirements. Test and publish consumer minima before 1.0.
Every increase requiring a consumer build change gets a `BREAKING` note.

Kotlin guarantees that newer stable JVM compilers read older binaries. Forward
compatibility with the next language release is best-effort, not guaranteed;
older compilers may reject new features. Thus a 2.3 compiler may read some 2.4
metadata, but **2.3 is not a guaranteed minimum for this SDK**. Use the pinned
2.4.10 compiler as the verified baseline until an older consumer is tested.
Do not infer support for 2.2 or earlier, or use skipped metadata checks as proof.
Lower `languageVersion`/`apiVersion` settings can preserve older consumer
compatibility, but require explicit configuration and verification.

## TVHeadend server compatibility

| Server / protocol | Current evidence and scope |
|---|---|
| HTSP below 36 | Below the documented connection floor. Reported as `SessionFailure.SERVER_UPGRADE_REQUIRED`; upgrade before explicit retry (`docs/consumer-guide.md:128-130`). |
| HTSP 36 or newer | Meets the documented floor. This is not proof that every server build, permission set or playback format works. |
| TVHeadend `4.3-2735~gfcd987f0b` | Historical device acceptance on 2026-08-24 and 2026-08-25: live H.264/audio, timeshift, completed-recording resume, session/repository and cross-process progress checks (`docs/playback-device-verification.md:3-19`; `docs/sdk-acceptance.md:3-6`, `docs/sdk-acceptance.md:46-87`). Not fresh 1.0 evidence. |
| Upstream revision `a87be591fa5f1b0ec9223abe2c52269bcbf31156` | Source advertises HTSP 44 and negotiates the lower client/server version. Source inspection only, not SDK runtime acceptance. |

For the last row, see upstream [`src/htsp_server.c:54`](https://github.com/tvheadend/tvheadend/blob/a87be591fa5f1b0ec9223abe2c52269bcbf31156/src/htsp_server.c#L54)
and [`src/htsp_server.c:1487-1502`](https://github.com/tvheadend/tvheadend/blob/a87be591fa5f1b0ec9223abe2c52269bcbf31156/src/htsp_server.c#L1487-L1502).
Do not infer a supported TVHeadend release number from the protocol number.

The SDK delegates connection negotiation to the HTSP dependency
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:206-212`).
It maps `UNSUPPORTED_SERVER_VERSION` through the gateway to the upgrade outcome
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/gateway/htsp/HtspProtocolGateway.kt:1197`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/session/ConnectionOwner.kt:846`).
Feature gates remain separate: recording progress requires protocol 27 and
cutpoints require 12 (`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrProgress.kt:15`;
`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/DvrCutpoints.kt:9`).
Unknown/older versions disable those operations
(`sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/session/DvrProgressCoordinator.kt:56-65`).
Those feature thresholds do not lower the connection floor. Server permissions,
configuration and operation outcomes still determine availability.

For 1.0, growing recordings remain MPEG-TS only; growing Matroska/MP4 retain
`GROWING_RECORDING_DEFERRED`. Raw teletext decoding is outside the 1.0 scope.
Live server-extracted text subtitles are supported. Later minors may add these
capabilities (`docs/roadmap.md:102-114`).

## Open questions for the owner

- **Fresh 1.0 server evidence:** which exact server build and negotiated HTSP
  version will qualify 1.0? Recommendation: record both with the SDK/HTSP/Media3
  pins during the fresh device matrix required by `docs/roadmap.md:238-244`.
  The August records name the build but not its negotiated HTSP version.
- **Supported server range:** which release builds establish the oldest and
  newest tested endpoints? Recommendation: retain protocol 36 as the floor,
  qualify concrete builds at that floor and the current upstream line, and
  claim only tested combinations. No exact release-to-protocol range is proven.
- **Consumer toolchain minima:** can older Kotlin/AGP/JDK combinations consume
  the artifacts? Recommendation: use the current pinned matrix initially;
  publish lower minima only after staged consumer checks, including R8 and lint.
- **Platform retirement:** approve minor-release `minSdk` increases with advance
  notice? Recommendation: adopt this draft rule; keep JVM runtime and server-floor
  increases major-only. These are policy choices, not existing gates.
