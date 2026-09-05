# Review Routing

For non-trivial non-UX work, use an independent Astra primary reviewer and an
independent Opus second reviewer, neither the implementing primary. Supply the
same bounded change, acceptance criteria and test evidence. The second initial
packet must not disclose the first verdict or findings. The primary adjudicates
supported defects and owns corrections. No automatic third review or broad
repeat audit; followups cover unresolved findings or material changes only.
Routine low-impact work and release ceremony alone do not require a pair.
Screenshot-first UX roles are separate from engineering review.

## Dispatch Guard

Before every Opus dispatch, including followups and UX requests, execute:

```bash
./review-provider-route.sh select eligible
```

Only exit success with stdout exactly `opus` permits Opus. Attach that dispatch's
guard evidence to its packet. Do not use cached eligibility, fixture mode or the
static default/release selectors to authorize a live call. The guard reads its
established credential internally, uses an in-memory cookie jar and emits only
the route and sanitized reason. Never source it or its credential file.

`astra` output means a separate independent Astra fallback session. Missing guard,
nonzero exit, unknown output or unavailable quota telemetry also means Astra.
There is no compatibility alias. Record the reason, actual reviewer/session and
explicitly absent Opus coverage. `status` reports routing eligibility, not review
requirements; its static modes cannot authorize Opus.

Fallback cannot satisfy an explicitly non-substitutable admitted gate. Report
that exact boundary centrally for supported reconciliation. Do not edit immutable
manifests/results, waive a gate or report fallback as Opus review.

## Actual Exhaustion

If an Opus call actually exhausts quota, use the established authenticated session
transport to `POST /session/{exact-reviewer-id}/abort` on that reviewer's server.
Verify that exact session is no longer busy/retrying before proceeding. Never
abort the implementing primary or unrelated sessions. Do not wait hours for a
reset, nudge/retry the exhausted reviewer or repeatedly spawn replacements.
Continue the independent Astra fallback and authorized independent work.
The `opencode-headless-sessions` skill describes the supported transport.

## Checks

Run `bash -n review-provider-route.sh test-review-routing.sh` and
`./test-review-routing.sh` after changes to this route. Reuse its existing quota,
redaction and permission fixtures; do not add tests that mirror model names or
policy prose. Config-time agent/skill changes apply to newly loaded sessions;
the runtime owner must reload through its supported mechanism, not an unapproved
shared-server restart by a product worker.
