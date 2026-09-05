# SDK engineering improvement: P25-S2

## Scope and incident preservation

Starting HEAD: `f43eaef9c0b04293e705b8cbfb26e3873857fb54`, clean `main`.
This is the SDK-owned evaluation of the original coordination-workspace outcome
and P25-S1 manifest, not a replacement for either immutable contract.

The P25-S1 blocked result is preserved at
`control_plane/results/P25-S1/result-e77de4e75561a9958de949dca102f5e9.json`
in the coordination workspace, SHA-256
`2b77e4566c02953dbbb6311aa7e84b7093395403620ab06c7efada989a5c4905`.
It records 259 core tests, two failures and one skip after an inherited credential
variable activated live EPG/DVR tests. Playback and Media3 passed. Failure before
readiness does not establish absence of server effects. No live configuration was
replayed here; no credential contents or raw incident logs were read or copied.

Original exclusions remain: no speculative cleanup or optimization, quotas for
findings/deletions/tests/commits, incidental public API or decoder changes, product
redesign, new repository frameworks, weakened gates, sibling/global/harness edits,
server/device operations, history rewrite, 1.x release, or bypass of exact-content
external-action approvals. Discovery stopped at the shortlist below. No new
backlog or successor package is proposed.

## Ranked shortlist

| Rank and candidate | Exact locations and observed evidence | Benefit, risk and checks | Decision |
|---|---|---|---|
| 1. Separate live tests from ordinary core verification | `sdk-core/build.gradle.kts:55-73`; inherited root `build.gradle.kts:456-458` previously enabled all JUnit tags. `sdk-core/src/test/kotlin/at/bernhardberger/tvheadend/sdk/core/session/EpgSoakVerificationTest.kt:35-37`, `sdk-core/src/test/kotlin/at/bernhardberger/tvheadend/sdk/core/session/DvrRealServerVerificationTest.kt:60-62`, and `sdk-core/src/test/kotlin/at/bernhardberger/tvheadend/sdk/core/session/DvrRealServerDenialVerificationTest.kt:45-47` use environment-only activation. Confirmed defect by source and preserved incident, not a performance hypothesis. | Makes existing `:sdk-core:test`, `build`, and `check` independent of live credentials and disabled JUnit conditions. Risk: losing access to live coverage or inadvertently dropping offline coverage. Check the full offline suite with absent and invalid credential inputs, conditions disabled, and dry-run discovery of the explicit live task. | Retain the bounded build change and an isolation/diagnostic recipe. |
| 2. Reduce repeated EPG coverage scans | `sdk-core/src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/metadata/EpgReducer.kt:487-493,580-590` filters visible events per channel and computes extrema. Repeated traversal/allocation is observed; meaningful runtime cost is only a hypothesis. | Potential catalog/Guide CPU and allocation reduction. Risk: coverage semantics, unknown-channel filtering, retention and query authority. Would require a representative mixed add/update/delete/query workload with channel/event distribution, repeated timing/allocation measurements and identical snapshots/resource bounds. Existing `sdk-core/src/test/kotlin/at/bernhardberger/tvheadend/sdk/core/metadata/EpgReducerTest.kt` covers 16 cases. | Reject for this slice. No representative before/after performance evidence; no performance change or speed claim. |
| 3. Remove a thin observation wrapper or its test | `sdk-testing/src/main/kotlin/at/bernhardberger/tvheadend/sdk/testing/FakeSessionObservation.kt:18-35`; `sdk-testing/src/test/kotlin/at/bernhardberger/tvheadend/sdk/testing/FakeSessionObservationTest.kt:19-36`. `publish` is thin, but capture/retirement enforce opaque capability and aggregate-observation semantics. | A few fewer lines would not demonstrate an existing-task benefit. Removing the boundary leaks mutable state and weakens supported consumer behavior; the test checks identity and illegal current/retired transitions, not just implementation structure. | Retain wrapper and meaningful test. No redundant protection established. No public fixture changes or new external-consumer requirement. |
| 4. Simplify playback rebase state or packet decisions | `sdk-playback/src/main/kotlin/at/bernhardberger/tvheadend/sdk/playback/SubscriptionTimestampRebasing.kt:5-26,80-101,121-151`. Two discard counters have different purposes: timed keyframe fallback versus bounded untimed failure. Track allocation occurs on track changes, not every packet. | Hypothetical smaller state/allocation footprint. Risk: stalled playback, shared-track offset or fallback regressions. `sdk-playback/src/test/kotlin/at/bernhardberger/tvheadend/sdk/playback/SubscriptionTimestampRebaseTest.kt` protects the behavioral boundary. Packet-distribution, seek and resource measurements would be needed for optimization. | Reject speculative simplification; preserve the distinct counters and coverage. |

No obsolete compatibility path, dead code, duplicate public model, or removable
implementation-coupled test was established in these inspected boundaries. That
is a bounded conclusion, not a claim of a repository-wide absence.

## Revalidated P24 leads

- P24-S1 succeeded at the starting SDK HEAD. Its final result records executable
  staged and published-only 0.6.1 consumer evidence, five modules without local
  substitution, and successful CI including run `33962413629`.
- P24-A3 succeeded at `c2a535ca79aeed9b18665994c3ce6fb6c5e3cce3` with published
  0.6.1 adoption, host regressions, actual emulator screenshots/instrumentation,
  and exact-commit CI `33966051901`. These facts are reused from its result;
  this SDK package performed no device work.
- `.github/workflows/ci.yml:32-37` uses this repository's wrapper and its local
  `consumer-contract`, not a sibling verification script. Published-fixture,
  screenshot and historical standalone-CI leads are resolved, not fresh defects.

## Retained change and boundary

The default core `test` task excludes `live-soak` and `live-dvr` at JUnit discovery.
An explicit `liveServerTest` task uses the same compiled tests and runtime
classpath but includes only those tags. It is not attached to lifecycle checks.
It cannot reuse prior task outputs because external server state and credentials
are not reproducible build inputs. Condition-skipped success is not live acceptance.
The environment conditions and all production/test bodies remain unchanged.
The selection regressions in `docs/offline-verification.md` reuse these actual
test classes; no synthetic server, custom checker or test framework was added.

This separates two distinct authorization boundaries rather than deleting
coverage: 256 offline core tests still execute, including fake-gateway lifecycle,
EPG, DVR and module-boundary checks. Three live tests remain discoverable by the
explicit task under separate server authorization. No public SDK behavior or
ABI changes. No SDK version bump, signing, tag or Maven publication is necessary
for this repository-only verification change.

## Offline evidence

Before the first rerun, inspected root/module build configuration, settings,
repository properties, test environment/property lookups and factory construction.
The only credential-triggered host workflows found were the three tagged core
classes. The factory test constructs/shuts down a production owner but does not
connect; other exercised protocol behavior uses injected fakes. The Gradle user
home had neither a properties file nor init scripts; repository `local.properties`
only selected the installed Android SDK.

Every Gradle invocation here uses the checked existing
`/tmp/tvheadend-player-gradle-0/gradle.lock`, an `env -i` allowlist, JDK 21,
`--offline --no-daemon -Ptvheadend.htsp.composite=false`, and `unshare --net` with
only namespace-local loopback enabled. Inherited credentials, Gradle project
environment inputs and Java option variables are absent. Explicit negative-test
inputs below are non-secret `/dev/null` paths, never real credential files.
There is no route to an external server even if test selection regresses.

| Run | Executable evidence and result |
|---|---|
| Unchanged baseline, cleared environment | `:sdk-core:test --rerun`, successful; 256 offline tests execute, three live tests skipped by their conditions. |
| Changed default, cleared environment | Same task, successful; 24 report suites, 256 tests, zero skipped/failures/errors; all three live classes absent. |
| Changed default, both credential variables set to `/dev/null` | Same task with reused configuration cache, successful; 24 suites, 256 tests, zero skipped/failures/errors. |
| Changed default, invalid credential inputs plus `JAVA_TOOL_OPTIONS=-Djunit.jupiter.conditions.deactivate=*` | Same task, successful; 24 suites, 256 tests, zero skipped/failures/errors; live classes absent from XML reports. The worker output confirms the deliberate property was picked up. |
| Explicit live selection, same invalid inputs and condition override | `:sdk-core:liveServerTest --test-dry-run --rerun`, successful; exactly the three existing live classes, one skipped test each, zero failures/errors. No live test body executed. |
| Final task-reuse correction | Repeated poisoned-input ordinary suite: 24 suites, 256 tests, zero skipped/failures/errors, live classes absent. Explicit live dry-run executed twice, second invocation without `--rerun` still executed rather than reusing outputs; three skipped tests, no bodies. |

The DX benefit is reproducible selection and successful execution of an existing
verification task in the presence of formerly activating inputs. Build wall times
are not a speed benchmark. No live TV, server-effect, decoder, performance or
device acceptance claim follows from these host checks.

## Final checks and delivery

- Final `./gradlew build check stageLocalPublication`, with the isolation flags
  above: successful, 281 tasks (37 executed, 244 up-to-date). The preceding full
  run passed before the narrowly reviewed task-reuse correction. Unchanged
  successful host checks were reused, not cleaned or repeatedly forced.
- Maintained JVM ABI checks, explicit Android compilation, dependency boundaries,
  class-file major 61, detekt/lint and Android test APK compilation passed. APK
  compilation is not instrumentation execution. No public fixture changed;
  existing P24 external-consumer evidence remains applicable.
- `tools/check-staged-publication` passed in a cleared environment and isolated
  network namespace: five modules, 26 originals, metadata, legal notices and
  FFmpeg payload/source. Staging is local verification, not publication.
- Independent Astra `ses_f8df5dfdeffed9n77Qx8wk9hlA`: CLEAN initial review and
  bounded closure of the task-reuse change. Independent Opus
  `ses_f8df5dd81ffeZTXqmnsuDHDsZz`: CLEAN after adjudication. Before both Opus
  dispatches, `./review-provider-route.sh select eligible` exited successfully
  with stdout `opus`. No quota fallback or absent Opus coverage.
- Opus F1 accepted: prevent reuse for external live state. The suggested
  dry-run-to-real sequence itself changes Gradle's dry-run input, but repeated
  condition-skipped or real runs still justified the fix. F2 rejected as a
  coverage-scope expansion: actual-class executable selection regressions cover
  this change; a hypothetical future untagged class does not justify a new
  checker here. F3 rejected: intentional host installation/example paths in a
  developer guide are not credential/profile data. F4 rejected: a future root
  reconfiguration is hypothetical; current framework ordering passed execution.
  Opus accepted these adjudications with no present defect remaining.
- Future new live workflows still require explicit classification and activation
  inspection. The tag rule is not a sandbox against arbitrary future test code;
  this evaluation's network namespace supplies the independent server boundary.
- The authorized single commit and non-force `origin/main` delivery are bound to
  their exact HEAD and authoritative CI run in the immutable P25-S2 result, after
  remote verification. That result avoids a self-referential commit hash here.
  CI must include the unchanged workflow's build/check/staging and staged-consumer
  checks. No version, tag, signing or Maven release is needed or performed.
