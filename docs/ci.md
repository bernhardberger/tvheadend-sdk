# Ordinary CI

The required `verify` job retains the clean SDK build/check/staging, publication
verification, clean consumer tests and dependency-graph check, and release setup
checks. `build check` is one Gradle task graph, not duplicate task execution.
First-attempt main push staging remains bound to the exact commit and run, as
specified in [releasing](releasing.md). No release credentials enter ordinary CI.

## Cache and cancellation boundaries

`setup-gradle` is pinned to the same Gradle Actions v6.3.0 commit as the separate
wrapper validator. Wrapper validation still runs before Gradle setup. Only a
`push` to `refs/heads/main` may write caches; PRs consume them read-only. GitHub's
cache branch isolation prevents main from restoring PR merge-ref caches.

The explicit `enhanced` provider supports the narrow include list; `basic` does
not. This is Gradle's proprietary caching component, free for public repositories,
under its [distribution and data-handling terms](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/DISTRIBUTION.md).
It processes public dependency artifacts; no release or server secrets are supplied.

The Gradle User Home include list is limited to `caches/modules-2` (downloaded
dependencies); the action also manages wrapper distributions. Project build
directories, local task-output caches, configuration-cache state, Gradle user
properties, init scripts and credentials are not included. No encryption secret,
dependency submission, PR comment or Build Scan publication is configured.
Tests and publication outputs are rebuilt rather than restored across runs.
The [offline/live test boundary](offline-verification.md) remains unchanged.
Restored dependencies remain subject to the checked-in Gradle dependency
verification metadata and signature/checksum policy. Compiled build scripts,
instrumented jars and artifact transforms are also deliberately excluded: this
first change reuses downloaded inputs only, not derived build state.

Concurrency groups include the workflow and event. PRs share a group only with
runs of the same PR and cancel obsolete runs. Main pushes use unique run IDs,
so neither newer main pushes nor PRs cancel or replace pending staging runs.

## Timing baseline

GitHub job/step timestamps from successful main runs, before explicit caching:

| Run | Commit | Created to job start | Job execution | Build/check/stage | Consumer | Android setup |
| --- | --- | --- | --- | --- | --- | --- |
| [34086193977](https://github.com/bernhardberger/tvheadend-sdk/actions/runs/34086193977) | `8fcc0cb` | 3s | 7m03s | 5m34s | 58s | 19s |
| [33973346513](https://github.com/bernhardberger/tvheadend-sdk/actions/runs/33973346513) | `8f30f58` | 3s | 8m16s | 6m28s | 1m19s | 17s |

Created-to-job-start is queue/startup delay, not build execution. These runs had
no configured cross-run Gradle cache; runner-provided cache state is unknown.
Their source workloads differ from the current SDK, so they are context, not
a controlled speedup comparison. Failed run `34086747566` stopped at publication
verification and is not a full-job performance baseline.

Retain `clean` and exclude task-output caching: no measured benefit justifies
expanding reusable outputs or changing exact-source staging assumptions. Android
setup was a small part of execution; neither setup changes nor splitting the
required job is justified by this evidence. Dependency caching targets repeated
downloads without changing verification. Warm-cache savings require a subsequent
natural run and remain unmeasured until observed; do not trigger benchmark-only
reruns. Record final exact-HEAD run timing and cache hit/miss evidence with delivery.
Also confirm caching was not disabled because of a pre-existing Gradle User Home;
a successful build alone does not establish that caching was active.
