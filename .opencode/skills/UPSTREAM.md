Repository: https://github.com/chrisbanes/skills
Release: 2026.10.8
Commit: c3b83285434c185e2ffbcd689c7b73a5a7892070
Copied verbatim:
- skills/kotlin-api-design/ -> .opencode/skills/kotlin-api-design/
- skills/kotlin-concurrency-and-flow/ -> .opencode/skills/kotlin-concurrency-and-flow/
- skills/kotlin-control-flow/ -> .opencode/skills/kotlin-control-flow/
- skills/grounded-writing/SKILL.md and references/ -> .opencode/skills/grounded-writing/
Links to the upstream Compose skills are intentionally not vendored; the SDK
contains no UI code. The Codex-only skills/grounded-writing/agents/ metadata
is not vendored.
License: .opencode/skills/chrisbanes-skills-LICENSE.txt

Repository: https://android.googlesource.com/platform/frameworks/support
(GitHub mirror https://github.com/androidx/androidx)
Commit: 421abfa7fff454ae9ba281ef0a0455be089c86a3
Copied with one modification:
- .agents/skills/ktdoc_quality/SKILL.md -> .opencode/skills/ktdoc-quality/SKILL.md
  The frontmatter name is changed to ktdoc-quality because OpenCode skill names
  must be lowercase and hyphenated, and a modification notice is added. The
  body is otherwise unchanged; its Compose and Android examples illustrate the
  style and do not apply to this SDK's code.
License: .opencode/skills/androidx-LICENSE.txt (no upstream NOTICE file)
