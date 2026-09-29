---
name: commit
description: Generate a Korean commit message in Conventional Commits style and execute the commit
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*)
---

# commit

Run shell commands from the repository root using the host's available shell tool. The `allowed-tools` frontmatter is Claude Code-specific; Codex uses its own permission configuration.

## Workflow

1. Run `git status` and `git diff --staged` in parallel to understand the changes
2. Analyze the intent and decide the type
3. Write the message and commit immediately (no user confirmation)

## Types

`feat` `fix` `refactor` `test` `docs` `chore`

## Message rules

- Format: `<type>: <subject>`
- Korean, present tense ("추가", "수정"), no period, ~50 characters
- Body only when the "why" needs explaining: **one line** stating the reason for the core change (separated from subject by a blank line). No multi-line bodies, no bullet lists.
- Do **not** include `Co-Authored-By` or tool attribution

## Commit command

```bash
git commit -m "$(cat <<'EOF'
<subject>

<body (if needed)>
EOF
)"
```

Never stage sensitive files (`.env`, key files, etc.) without explicit user confirmation.
