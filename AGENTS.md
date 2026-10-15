# TVHeadend SDK engineering guide

Guidance for anyone changing this repository, whether working by hand or with
a coding agent. Repository tests and CI override generic advice, including the
optional agent and skill under `.opencode/`.

## Provenance

This GPLv3 SDK is an independently maintained client library for TVHeadend. It
links the GPLv3 HTSP protocol library. Preserve all license and attribution
notices, and do not describe this project as official TVHeadend software.

## Working style

- Keep changes minimal and scoped. Check `git status` before editing and never
  overwrite someone else's uncommitted changes.
- Behavior changes ship with a focused regression test.
- Use constructor-injected fakes. Do not add dependency injection, mocking,
  screenshot, coverage, or UI frameworks without a concrete need.
- Before writing a parser, codec, crypto implementation, discovery stack,
  serializer, or time conversion, identify the maintained library that should
  provide it and explain why it does not fit. If none exists, say so in the
  commit body. For example, Media3 provides
  `androidx.media3.container.NalUnitUtil` for H.265 SPS parsing.
- Prefer standard maintained tooling: Gradle, Kotlin plugins, detekt, Konsist,
  Dokka, Kotlin ABI validation, and GitHub Actions. Do not add bespoke checkers,
  generators, scripts, languages, or repository frameworks without explicit
  maintainer approval.
- Use Conventional Commit subjects (`feat(core):`, `fix(media3):`, `docs:`,
  `chore:`), with `!` and a `BREAKING` changelog note for incompatible changes.
- Non-trivial changes to public API/ABI, lifecycle, concurrency, playback state
  machines, or the release path deserve an independent review before they land.
  The optional `sdk-reviewer` agent in `.opencode/agents/` is one way to get it.

## Where to look

| Work | Read first |
|---|---|
| Unknown owning module or call path | `docs/module-map.md` |
| Observable SDK API, lifecycle, playback, gateway or consumer contracts | `.opencode/skills/tvheadend-sdk-contract-change/SKILL.md` |
| Application-facing API and consumer usage | `docs/consumer-guide.md` |
| Offline checks and live-test selection | `docs/offline-verification.md` |
| Versioning and compatibility | `docs/versioning.md` |
| Release preparation, signing, publication | `docs/releasing.md` |

## Module boundaries

- `sdk-core` owns protocol integration, lifecycle, application-safe models,
  metadata, EPG, and DVR workflows. It is pure JVM.
- `sdk-playback` owns subscription, seek, timeshift, and timestamp state
  machines. It is pure JVM.
- `sdk-media3` owns Android Media3 source, period, elementary-stream adapters,
  and the narrow TVHeadend playback coordinator. It has an explicit API
  dependency on `sdk-core`; do not duplicate core models behind a mirror API.
- `sdk-android` owns Android discovery, connectivity, credentials, and artwork
  integration.
- `sdk-testing` supplies JVM-only fakes, repositories, scripted events, and
  packet fixtures.
- `sdk-core`, `sdk-playback`, and `sdk-testing` must not resolve Android,
  Media3, or native artifacts.
- HTSP imports are confined to the gateway implementation layer. No HTSP type
  may appear in a public SDK signature.
- The SDK contains no UI and does not own `Player`, `MediaSession`,
  notifications, ViewModels, navigation, or product recommendation policy.
- The playback coordinator borrows an application-owned `Player`. It may install
  and retire TVHeadend sources and listeners, but never constructs or releases
  the Player or owns MediaSession, service, audio focus, notifications, surfaces,
  autoplay, navigation, or presentation policy.

## API and runtime invariants

- Use explicit Kotlin APIs and track every hand-written public declaration in
  ABI dumps where the pinned toolchain supports them. With AGP 9.4.1's built-in
  Kotlin path, KGP 2.4.10's experimental ABI tasks register no Android variant
  binaries (KT-83410), while binary-compatibility-validator 0.18.1 requires the
  absent `kotlin-android` plugin. Keep Android public APIs explicit and re-enable
  their ABI dumps only after maintained tooling supports built-in Kotlin.
- Every public suspending server round trip returns a typed outcome.
  `CancellationException` always propagates and is never converted to a failure
  value.
- Errors, diagnostics, cache keys, logs, test failures, and `toString()` output
  must not expose credentials, tickets, paths, endpoints, hostnames, usernames,
  raw server errors, or subscription ids. The sole exception is experimental,
  display-only DVR mutation `serverMessage`: sanitized and bounded, never in
  `toString()`, logs, equality, or diagnostics.
- Exactly one server profile and protocol connection may be active. Switching
  profiles performs full teardown, repository reset, reconnect, and sync.
- Applications fake the SDK boundary. SDK tests above the gateway fake the
  internal protocol gateway, not `HtspConnection`; only the gateway's own tests
  fake `HtspConnection`.
- Compatibility shims for pre-1.0 SDK APIs are not required for the in-repo app
  consumer. Make clean SDK API changes and migrate that app together with them.

## Build and verify

On hosts that install the shared build launcher
(`/usr/local/bin/tvhplayer-verify`), use `./tools/verify [Gradle tasks/options]`
for **all local Gradle builds**, including staged-publication and
`-p consumer-contract` runs. With no arguments it runs `build check`. The
launcher runs single-use Gradle with in-process Kotlin inside a memory-bounded
build slice shared with other projects, so avoid overlapping heavy verification.
Direct `./gradlew` bypasses containment; do not retry a cancelled or OOM build
that way. Other hosts retain normal Gradle behavior. Cold-cache checks are
exceptional: point `GRADLE_USER_HOME` at a fresh disk-backed directory, never a
tmpfs. Retain ordinary persistent caches; do not clean by default.

The checked-in Gradle wrapper is the build prerequisite. JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- Run the affected module's tests while developing and `./gradlew build check`
  before submitting. Do not clean by default.
- Publication or build-logic changes also run the staged publication and
  consumer-contract steps from `ci.yml`.
- Use JDK 21. JVM publications target Java 17 and class-file major 61.
- Update ABI dumps only through the Gradle ABI validation workflow, never by
  hand.
- Local cross-repository HTSP substitution is opt-in
  (`-Ptvheadend.htsp.composite=true`). The pinned version of
  `at.bernhardberger.tvheadend:htsp` is recorded in `gradle/libs.versions.toml`.
  Local binary verification of an unreleased HTSP candidate opts into the HTSP-only repository with
  `-Ptvheadend.htsp.candidateRepository=/absolute/path/to/candidate-maven`;
  CI and releases do not add that repository automatically.
- Tests that reach a live server or device are opt-in; see
  `docs/offline-verification.md`. Live-server runs and device operations
  require explicit maintainer authority; loading this guide or a skill grants
  none.

## Release trust boundary

- Staging a local Maven repository is verification, not publication.
- Commits, tags, pushes, pull requests, signing, publication, releases and
  credential access require explicit maintainer authority covering the
  operation and target. Preparing or checking release files never authorizes
  any of them.
- Never print secrets or place them in source, arguments, artifacts, logs,
  reports, generated output, or Gradle dependency verification metadata.
- For release work, read `docs/releasing.md` and command `--help` output. It
  owns the authorization, credential and artifact-verification procedure.

## Device tooling

- Use `tools/sdk-device` for SDK test APK installation, instrumentation,
  app-private `run-as` operations, and cleanup instead of raw ADB command
  sequences. Do not embed device endpoints in source or instructions.
- The helper never provisions credentials.
