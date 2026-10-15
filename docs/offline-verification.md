# Offline verification

`:sdk-core:test` excludes the JUnit tags `live-soak` and `live-dvr`. Neither a
credential-file environment variable nor a JUnit condition override opts `test`,
`build`, or `check` into these server workflows. The live tests remain compiled;
their source and assertions are unchanged.

`:sdk-core:liveServerTest` selects only those tags and is not a dependency of
`test`, `build`, or `check`. Executing it requires separate authorization for
server reads and DVR mutations. A credential file is configuration, not
authorization. Do not run it as an offline recovery step. The existing environment
conditions remain additional safeguards, not the offline isolation boundary.
The explicit task does not reuse prior task outputs: external server state and
credentials are not reproducible build inputs. Missing credentials can still
skip its tests; a successful task with skipped tests is not live acceptance.

## Isolated host checks

Gradle `--offline` prevents dependency downloads, not network calls made by tests.
For strict offline evaluation on the Linux coordination host, clear inherited
inputs and use an isolated network namespace. Only its loopback interface is
enabled, for Gradle's local worker/daemon communication. There is no external
interface or server route. Do not substitute a normal network run if isolation
fails.

Inspect the build/test activation sources before running checks. Confirm the
Gradle user home has no unreviewed `gradle.properties` or init scripts; inspect
repository properties and explicit task selection too. Never read credential
contents. `env -i` removes inherited credential variables, `ORG_GRADLE_PROJECT_*`,
`GRADLE_OPTS`, `JAVA_OPTS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and
`JDK_JAVA_OPTIONS`. `--no-daemon` avoids reuse of a daemon from a live environment.
Use an installed JDK 21 and Android SDK, and cached pinned dependencies.

After checking the existing lock parent, the command used for P25-S2 was:

```sh
flock /tmp/tvheadend-player-gradle-0/gradle.lock \
  unshare --net sh -c 'ip link set lo up && exec env -i \
    HOME=/root PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/bin:/bin \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk \
    ./gradlew --offline --no-daemon -Ptvheadend.htsp.composite=false build check'
```

The lock path is `/tmp/tvheadend-player-gradle-$(id -u)/gradle.lock` on other
accounts. Adapt installation paths without inheriting the surrounding shell's
environment. This is a host recipe, not a new repository wrapper or a requirement
for ordinary CI runners. It blocks server traffic independently of test selection.

## Selection regressions

Use the same isolated command prefix above. These checks reuse the actual live
test classes and the existing offline suite rather than mock their activation:

1. Run `:sdk-core:test --rerun` in the cleared environment. The offline tests must
   execute successfully, and the three live verification classes must be absent
   from `sdk-core/build/test-results/test/TEST-*.xml`, not merely skipped.
2. Repeat with both `TVHEADEND_SOAK_CREDENTIALS_FILE=/dev/null` and
   `TVHEADEND_SOAK_NODVR_CREDENTIALS_FILE=/dev/null` added after `env -i`. These
   deliberately invalid, non-secret inputs must not change ordinary test selection
   or cause credential parsing. Never use a real credential path for this check.
3. To stress disabled environment conditions, also add
   `JAVA_TOOL_OPTIONS=-Djunit.jupiter.conditions.deactivate=*` as a single quoted
   environment assignment. Ordinary tests must still execute and the live classes
   must remain absent. This deliberately injected test input is not inherited
   configuration.
4. With those same invalid inputs, use
   `:sdk-core:liveServerTest --test-dry-run --rerun` to verify the separate task
   discovers exactly the three live tests as skipped, without executing their
   bodies. Never omit `--test-dry-run` during offline verification.

Inspect only the sanitized run's reports. Preserve prior incident evidence by
its immutable result reference; do not publish raw live logs or mistake a failure
before readiness for proof that the server was unaffected.

## Staged consumer and R8

After staging and checking the SDK publication, run the consumer gate locally:

```sh
./tools/verify stageLocalPublication
./tools/check-staged-publication --write-manifest build/staged-publication.json
./tools/verify -p consumer-contract test verifyConsumerDependencyGraph assembleRelease lintDebug
```

CI uses `./gradlew --no-daemon -p consumer-contract clean test verifyConsumerDependencyGraph assembleRelease lintDebug`.
On the entity-model candidate branch, stage with
`./tools/verify -Ptvheadend.htsp.composite=true stageLocalPublication`, then add
`-Ptvheadend.htsp.candidateRepository=/absolute/path/to/candidate-maven` to the
consumer command. The consumer resolves the pinned HTSP binary from that opt-in,
HTSP-only repository; it does not substitute the sibling source checkout. Use
`--refresh-dependencies` when the same named SNAPSHOT has been restaged.

The unsigned release APK enables R8's default full mode without signing credentials.
The compile-only app has no manifest entry point, so its three Java/Kotlin fixture
classes are explicit app-side roots in `consumer-contract/proguard-rules.pro`.
Only those consumer calls are pinned; the SDK remains shrinkable and obfuscatable.
They cover the real cached session, discovery, connectivity, audio output, playback
coordinator, and consumer testing fakes. `sdk-testing` is test-only in real apps.

Inspect `consumer-contract/build/outputs/mapping/release/` (`mapping.txt`,
`usage.txt`, `seeds.txt`, and `configuration.txt`) for retained SDK call paths and
merged dependency rules. Cache serializers are statically generated and explicitly
referenced; kotlinx.serialization supplies its own consumer rules. Legacy NSD
reflection targets Android framework methods, which R8 does not rename.
No additional SDK consumer keep rules are currently needed.

Lint enforces AndroidX opt-in requirements that Kotlin compilation does not check.
`StableLoadControlConsumer.kt` deliberately has no Media3 unstable opt-in, proving
the SDK-owned load-control boundary remains usable without it. Java fixtures opt
into their SDK Kotlin markers at the methods that use them; no lint baseline is used.

This gate verifies compilation, lint and shrinking only. It does not execute the minified
APK; runtime verification requires separately authorized device testing.
