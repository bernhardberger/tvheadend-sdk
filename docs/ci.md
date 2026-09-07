# Ordinary CI

The required `verify` job retains the clean SDK build/check/staging, publication
verification, clean consumer tests and dependency-graph check, and release setup
checks. `build check` is one Gradle task graph, not duplicate task execution.
First-attempt main push staging remains bound to the exact commit and run, as
specified in [releasing](releasing.md). No release credentials enter ordinary CI.

## Cache and cancellation boundaries

SHA-pinned `actions/cache/restore` and `actions/cache/save` use explicit paths.
Wrapper validation still runs before cache restoration. Only a successful
`push` to `refs/heads/main` may save caches; PRs only run restore. GitHub's cache
branch isolation prevents main from restoring PR merge-ref caches. The
`sdk-dependencies-v1` namespace cannot match prior Gradle Actions cache entries.

The cached paths are limited to `~/.gradle/caches/modules-2` (downloaded
dependencies) and `~/.gradle/wrapper/dists` (wrapper distributions). Project build
directories, local task-output caches, configuration-cache state, Gradle user
properties, init scripts and credentials are not included. No encryption secret,
dependency submission, PR comment or Build Scan publication is configured.
Tests and publication outputs are rebuilt rather than restored across runs.
The [offline/live test boundary](offline-verification.md) remains unchanged.
Restored dependencies remain subject to the checked-in Gradle dependency
verification metadata and signature/checksum policy. Compiled build scripts,
instrumented jars and artifact transforms are also deliberately excluded: this
first change reuses downloaded inputs only, not derived build state.
`setup-gradle` was evaluated first. Run `34091334988` showed its enhanced provider
saving independently managed derived entries despite the narrow include list.
Explicit exclusions stopped those saves in `34092236058`, but that run still
restored prior derived entries. The basic provider does not support custom paths.
The maintained path-based GitHub cache actions avoid both behaviors without
deleting old remote caches, adding secrets, or depending on proprietary caching.

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
A successful build alone does not establish that cache restoration or saving
worked; inspect the named cache steps and their exact matched keys.
