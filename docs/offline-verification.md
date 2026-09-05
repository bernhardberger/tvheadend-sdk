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
