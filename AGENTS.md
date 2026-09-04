# TVHeadend SDK engineering guide

This GPLv3 SDK is an independently maintained client library for TVHeadend. It
links the GPLv3 HTSP protocol library. Preserve all license and attribution
notices, and do not describe this project as official TVHeadend software.

## Working style

- Keep changes minimal and scoped. Inspect `git status -sb` before editing and
  never overwrite existing uncommitted changes.
- Behavior changes ship with a focused regression test.
- Repository rules, the orchestration handoff, and tests override skill
  guidance.
- Use constructor-injected fakes. Do not add dependency injection, mocking,
  screenshot, coverage, or UI frameworks without a concrete package need.
- Before writing a parser, codec, crypto implementation, discovery stack,
  serializer, or time conversion, identify the maintained library that should
  provide it and explain why it does not fit. If none exists, say so in the
  commit body. The predecessor hand-wrote an H.265 SPS parser despite Media3
  already providing `androidx.media3.container.NalUnitUtil`; do not repeat that
  failure.
- Prefer standard maintained tooling: Gradle, Kotlin plugins, detekt, Konsist,
  Dokka, Kotlin ABI validation, and GitHub Actions. Do not add bespoke checkers,
  generators, scripts, languages, or repository frameworks.

## Package delegation

- One primary owns the task end-to-end. Routine work and ordinary releases need
  no planner, reviewer, package chain or coordinator. Split only for a real
  dependency, ownership or authorization boundary, not for workflow stages.
- Use read-only advisers for a concrete question that benefits from independence.
  Require one independent review for security-sensitive changes or substantial
  protocol, concurrency or public-contract changes; otherwise review is optional.
  Select one suitable configured reviewer, not a mandatory pair or provider.
- Give children the relevant diff, evidence, question and stop condition. They
  retain their configured permissions and cannot create a new work stream.
  The primary adjudicates findings and owns fixes. Re-review only a specific fix
  whose correctness remains uncertain, not the unchanged full packet.
- Model and effort choices live in OpenCode configuration, not product policy.
  Changing an assignment does not require editing these instructions. The old
  quota selector is optional; never source it or its credential file.
  Its legacy `sol`/`sol_required` output is a route contract, not a review mandate.
- Existing admitted manifests retain their explicit authority and gates. Do not
  silently weaken an in-flight package or revive a retired field-test role.

## Build and verify

The checked-in Gradle wrapper is the build prerequisite. JDK toolchains resolve
automatically. CI (`.github/workflows/ci.yml`) is the authoritative gate.

- During development, run the affected module's tests/checks. The full gate is
  `./gradlew build check`; stage publication only for publication/build changes
  or a release. Do not clean by default or rerun successful unchanged checks for
  each review. CI remains authoritative; add tests for concrete behavior, not
  scaffolding, model names, prompt prose or hypothetical acceptance expansion.
- Use JDK 21. JVM publications target Java 17 and class-file major 61.
- Update ABI dumps only through the Gradle ABI validation workflow.
- Local cross-repository HTSP substitution is opt-in. CI and releases always
  resolve the pinned `at.bernhardberger.tvheadend:htsp:0.7.0` coordinate.
- Start repository discovery with `docs/module-map.md`. Use direct search from
  its named entry points before delegating a locator or rebuilding a broad map.

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
  ABI dumps where the pinned toolchain supports them. With AGP 9.3.1's built-in
  Kotlin path, KGP 2.4.10's experimental ABI tasks register no Android variant
  binaries (KT-83410), while binary-compatibility-validator 0.18.1 requires the
  absent `kotlin-android` plugin. Keep Android public APIs explicit and re-enable
  their ABI dumps only after maintained tooling supports built-in Kotlin.
- Every public suspending server round trip returns a typed outcome.
  `CancellationException` always propagates and is never converted to a failure
  value.
- Errors, diagnostics, cache keys, logs, test failures, and `toString()` output
  must not expose credentials, tickets, paths, endpoints, hostnames, usernames,
  raw server errors, or subscription ids.
- Exactly one server profile and protocol connection may be active. Switching
  profiles performs full teardown, repository reset, reconnect, and sync.
- Applications fake the SDK boundary. SDK tests fake the internal protocol
  gateway, not `HtspConnection`.
- Compatibility shims for pre-1.0 SDK APIs are not required for the in-repo app
  consumer. Make clean SDK API changes and migrate that app in the same
  authorized package chain.

## Release trust boundary

- Staging a local Maven repository is verification, not publication.
- Never tag, push, create a pull request, sign, publish, release, or access
  credentials without an explicit maintainer instruction for that exact
  operation.
- Never print secrets or place them in source, arguments, artifacts, logs,
  reports, generated output, or Gradle dependency verification metadata.
- For release work, read `docs/releasing.md` and command `--help` output. Do not
  read `tools/publish-central-release` unless the package edits that tool or a
  reproduced failure has been attributed to its implementation. Run release
  setup validation once per release attempt, not once per review. One authorized
  task may prepare, verify, tag, publish and confirm availability; these stages
  do not require separate packages or model reviews. Publication still requires
  the exact maintainer authorization and artifact checks in `docs/releasing.md`.

## Device tooling

- Use `tools/sdk-device` for SDK test APK installation, instrumentation,
  app-private `run-as` operations, and cleanup. Do not reconstruct raw ADB
  command sequences or embed the G10 endpoint in package instructions.
- The helper never provisions credentials. Existing owner-only, one-use
  provisioning remains a separately authorized coordination-workspace action.
