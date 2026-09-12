---
name: tvheadend-sdk-contract-change
description: Use when changing observable TVHeadend SDK public API, repository observation, profile lifecycle, playback state-machine, gateway mapping, or consumer contracts. Routes implementation and verification across SDK modules. Do not load for unrelated documentation, instruction-harness maintenance, or release ceremony alone.
---

# SDK contract changes

Start with the requested observable behavior, supplied paths, owning declaration,
implementation and nearest regression. Use `docs/module-map.md` if ownership or
entry paths are unknown. `AGENTS.md` owns module, privacy, review and authority
rules; this skill does not replace them.

Read only the references needed for the affected workflow:

| Change or verification need | Reference |
|---|---|
| Application-facing API or consumer usage | `docs/consumer-guide.md` and the affected contract document |
| Offline core checks or live-test selection | `docs/offline-verification.md`; live work still needs separate authority |
| Media3 selection or stream replacement | `docs/media3-selection-contract.md` or `docs/stream-restart-contract.md`, respectively |
| Review dispatch | `docs/review-routing.md` |
| Published-coordinate verification or publication | `docs/releasing.md` and existing consumer-contract tasks |

Follow a reference's relevant section rather than loading the whole document
stack. Other contracts can be located from the affected source or module map.

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
- For public API changes, inspect supported ABI changes and affected Kotlin/Java
  consumers. Update dumps through the existing Gradle workflow, not by hand.
  Read the documented Android ABI limitation rather than creating a new checker.
- For dependency/publication changes, local substitution or staging proves only
  that local configuration; do not claim a published-coordinate result from it.
- Report the changed contract, consumer consequences and exact remaining gap.
  An authorized app migration may accompany a clean pre-1.0 API change; do not
  add compatibility layers speculatively. Commit/push/publication authority is
  still determined by the task, not by loading this skill.
