# Instruction harness

Use this document when maintaining SDK instructions, agents or skills. Product
implementation starts from its affected source and contracts.

## Active surfaces and ownership

| Surface | Owns |
|---|---|
| `AGENTS.md` | Repository domain invariants, completion, authority, delegation and verification requirements; contextual procedure pointers |
| `.opencode/opencode.json` | Primary model and effort defaults |
| `.opencode/agents/sdk-{locator,analyze,research,planner,implementer}.md` | Role triggers, configured settings, permissions, budgets and return contracts |
| `.opencode/agents/sdk-review-{astra,opus,muse}.md` | Independent reviewer scope, evidence and verdict contracts, configured settings and permissions |
| `.opencode/skills/tvheadend-sdk-contract-change/SKILL.md` | Observable-contract implementation workflow and task-specific references |
| `docs/review-routing.md` | Caller-side effort selection, review dispatch, quota fallback and exhaustion handling |
| `docs/module-map.md` | Source entry points when ownership is unknown |
| `docs/releasing.md` | Authorized release procedure and immutable publication gates |
| This document | Instruction maintenance and loading checks |

There are no repository-local command prompts or additional instruction files in
the project configuration. Global skills and runtime instructions are owned by
their installation; vendored dependencies and historical evidence are provenance,
not active repository policy to rewrite. Ordinary CI details live in `docs/ci.md`;
workflow-specific product documents are loaded only for the affected behavior.

Keep caller procedures in their owning document instead of repeating them in
each skill. Keep restricted-child contracts self-contained: the read-only SDK
children may not load skills or read repository `AGENTS.md`, orchestration ledgers
or handoffs.
In particular, the optional planner retains that exclusion. Its caller supplies
the coherent outcome, hard requirements, hypotheses, entry paths and evidence;
the primary retains scope authority and final decisions. Other restricted
children receive their relevant requirements, bounded question/diff and evidence.
Do not use a document pointer to bypass a child's read restrictions.

Agent frontmatter owns model, effort, permissions and step limits. Prompt bodies
also carry binding delegation, budget and evidence limits. Repetition needed for
standalone child operation is intentional. A documentation consolidation must
preserve both layers, including the implementer's restricted writable role and
the primary's integration responsibility. Task authority can further restrict a
role; loading it never expands authority.

## Verification and loading

- Run existing affected checks. For review routing and agent permissions:
  `bash -n review-provider-route.sh test-review-routing.sh`, then
  `./test-review-routing.sh`. These use fixtures, not live quota authorization.
- Inspect the scoped diff and compare agent frontmatter and project configuration
  exactly against the starting revision. Inspect body changes for delegation,
  budget, reviewer evidence/verdict and task-gate preservation.
- Use a fresh local OpenCode process from this checkout:
  `opencode debug agent <changed-agent>` and `opencode debug skill` exercise the
  configured loaders without launching model work. Compare the loaded prompt or
  skill content with the saved file. Inspect only relevant output; do not print
  unfiltered resolved configuration that could contain credentials.
- Report saved-file, fresh-loader and running-session evidence separately.
  Existing sessions may retain cached instructions or permission settings.
  Removing a stale reload reminder does not activate new settings. The runtime
  owner must use its supported reload/restart mechanism before relying on changed
  settings in existing sessions; a product worker must not restart a shared
  backend without authority. Fresh CLI loading alone proves no such activation.
- Documentation-only maintenance needs no new prose tests or full product build.
  Preserve explicit task gates and reuse successful checks for unchanged state.

These procedures work from a standalone clone. Centrally admitted work also
retains its supplied authority, reporting and delivery requirements; this
repository does not duplicate the central scheduler or require its workspace for
local instruction verification.
