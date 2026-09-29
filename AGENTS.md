# Project instructions

Before working on this repository, read and follow:
- `.claude/CLAUDE.md`
- All Markdown files in `.claude/rules/`

Paths in these instructions are relative to the repository root. Keep shared project guidance in `.claude/` so Claude Code and Codex use the same source.

## Skills

Shared skills live in `.claude/skills/`. `.agents/skills` is a symlink to that entire directory, so add and edit skills only in `.claude/skills/`.
Use the `commit` skill for requested commits and the `workflow` skill when explicitly requested. Read the corresponding `SKILL.md` directly if the skill is not available in the current session's skill list.

## Code review

When a PR review is requested, follow the review process and output format in `.claude/agents/pr-reviewer.md`. Its YAML frontmatter configures Claude Code only; use the current host's available tools and model.
