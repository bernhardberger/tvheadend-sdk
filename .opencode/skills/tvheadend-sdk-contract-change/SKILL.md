---
name: tvheadend-sdk-contract-change
description: Use for TVHeadend SDK public API, repository observation, profile lifecycle, playback state-machine, gateway mapping, or consumer-contract changes. Routes implementation and verification across SDK modules without leaking protocol or application policy into the wrong layer.
---

# SDK contract changes

Start with the requested observable behavior and `docs/module-map.md`, then the
owning declaration, implementation and nearest regression. `AGENTS.md` owns the
module, privacy, review and release rules; this skill does not replace them.

## Locate the boundary before implementing

- Trace one affected path from public SDK caller through the owning module to
  its gateway or platform adapter. Do not map the entire repository.
- Classify the defect: wire representation belongs to HTSP; application-safe
  semantics belong to the SDK; navigation, presentation and Player ownership
  belong to the app. A cross-repository fix needs the corresponding ownership
  and authority, not a workaround in whichever repository is writable.
- Reuse the current public models and maintained libraries before introducing
  another parser, adapter layer or mirror API. Keep Android/native dependencies
  out of the pure JVM modules and HTSP types out of public signatures.

## Implement the contract, not only the happy path

For affected asynchronous behavior, establish owner, lifetime and relevant stale
event boundary: profile switch, reconnect, subscription replacement or close.
Preserve typed outcomes and cancellation propagation. Test only the transitions
the change can affect, including a late result where that is the regression.
Do not invent a generic lifecycle framework for a single fix.

Use the internal protocol gateway fakes for SDK implementation tests and SDK
boundary fakes for application consumers. Do not substitute a live server or
mock `HtspConnection` merely because those are easier to reach. Keep failure
fixtures and diagnostics non-sensitive.

## Verify and deliver

- Run the affected module's relevant tests; follow `AGENTS.md` for final checks
  and independent review where the actual change requires it.
- Non-trivial non-UX changes use independent Astra primary and Opus second review
  of the same bounded evidence, with the second initial packet blind to the first
  verdict/findings. Run `./review-provider-route.sh select eligible` before every
  Opus dispatch, including followups; only explicit `opus` permits it. Follow
  `docs/review-routing.md` for independent Astra fallback, absent-Opus disclosure
  and exact-session abort on actual exhaustion. No third or broad repeat audit;
  preserve non-substitutable admitted gates. Low-impact work needs no mandatory pair.
- For public API changes, inspect supported ABI changes and affected Kotlin/Java
  consumers. Update dumps through the existing Gradle workflow, not by hand.
  Read the documented Android ABI limitation rather than creating a new checker.
- For dependency/publication changes, consult `docs/releasing.md` and existing
  consumer-contract tasks. Local substitution or staging proves only that local
  configuration; do not claim a published-coordinate result from it.
- Reuse unchanged passing checks. Do not add tests for skill prose or repeat a
  full build after a documentation-only correction.
- Report the changed contract, consumer consequences and exact remaining gap.
  An authorized app migration may accompany a clean pre-1.0 API change; do not
  add compatibility layers speculatively. Commit/push/publication authority is
  still determined by the task, not by loading this skill.
