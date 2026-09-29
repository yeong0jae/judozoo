---
name: workflow
description: Automatically infer the current development workflow state and proceed to the next step
disable-model-invocation: true
---

# workflow

A phase-by-phase development workflow orchestrator. Infers the current state from git/GitHub and walks through each step together with the user.

Run only when the user explicitly requests this workflow. Run shell commands from the repository root; repository paths below are relative to that root. Claude Code uses `disable-model-invocation` above; Codex uses `agents/openai.yaml` to preserve explicit-only invocation.

## State inference (run immediately on skill invocation)

```bash
LATEST=$(ls docs/tasks/[0-9][0-9][0-9]-*.md 2>/dev/null | sort | tail -1)
DONE=$(grep -c '^- \[x\]' "$LATEST" 2>/dev/null || echo 0)
TODO=$(grep -c '^- \[ \]' "$LATEST" 2>/dev/null || echo 0)
BRANCH=$(git branch --show-current)
DIRTY=$(git status --porcelain | wc -l | xargs)
ISSUE=$(gh issue list --state open \
          --label feat --label fix --label refactor --label chore \
          --json number --jq '.[0].number // empty')
PR=$(gh pr list --state open --head "$BRANCH" \
       --json number,reviewDecision \
       --jq '.[0] | "\(.number // "") \(.reviewDecision // "none")"')
echo "tasks=$LATEST done=$DONE todo=$TODO branch=$BRANCH dirty=$DIRTY issue=$ISSUE pr=$PR"
```

Map the collected signals using the table below:

| State | Signals | Next step |
|-------|---------|-----------|
| **A. No tasks** | All items in the latest `NNN-<title>.md` are checked | → Write the next phase's `NNN-<title>.md` referencing prd.md / spec.md |
| **B. No issue** | Unchecked tasks exist but no open issue for them, current branch is main | → Draft issue → user approval → `gh issue create` |
| **C. No branch** | Issue exists, current branch is main | → Suggest branch name (`<type>/#<n>-<slug>`) → user approval → `git checkout -b <name>` |
| **D. In progress** | On a work branch (`<type>/#<n>-...`), ≥ 1 unchecked item | → Work on the *next unchecked item* in the tasks file → on completion, auto-update `[ ]` → `[x]` + commit via the `commit` skill |
| **E. No PR** | Work branch, all items checked, no open PR | → Draft PR body → user approval → `gh pr create` |
| **F. Review in progress** | PR is open | → Ask the user if they want a review → if yes, fetch diff and comments, identify improvements, present options with recommendation and reasoning → apply approved items |
| **G. Awaiting merge** | PR is open | → Ask the user if they want a review → if no, ask for merge approval → merge |
| **H. Merged** | PR closed (merged), branch not yet cleaned up | → Checkout main + pull + delete local phase branch. Suggest writing next phase tasks |

## tasks authoring rules

- 1 phase = 1 tasks file (`NNN-<kebab-case-title>.md`, e.g. `015-백엔드-python-마이그레이션.md`). NNN is a zero-padded 0-based serial number.
- Every work item is a checkbox (`- [ ]`). One checkbox = one natural unit of work that can be completed in one go (typically 1–3 checkboxes = 1 commit).

## Issue creation rules

- Only in state B (tasks exist + no open issue). Never create an issue before writing tasks.
- Choose one template that fits the work: `feature` (new feature / phase work) / `fix` (bug) / `refactor` (structural improvement) / `chore` (environment/tooling).
- Title uses the template prefix (`feat: ` / `fix: ` / `refactor: ` / `chore: `) + one-line summary.
- Body follows the section structure of `.github/ISSUE_TEMPLATE/` at the repository root.
- Extract the issue number from the response immediately after `gh issue create` → feed it into state C (branch creation).

## Branch creation rules

- Work unit: `docs/tasks/NNN-<kebab-case-title>.md`
- Branch: `<type>/#<issue-number>-<slug>`
  - Examples: `feat/#1-bootstrap`, `chore/#5-ddl-rollout`
  - Default types: `feat` `fix` `refactor` `test` `docs` `chore`
  - Extract the issue number from `gh issue create` output (`gh issue view --json number`)
  - Branch name contains `#`, so quotes are required: `git checkout -b "<name>"` (prevents shell comment interpretation)
- Base branch: `main`

## PR creation rules

- Only in state E (all checkboxes done + no open PR). Never create a PR with unchecked items remaining.
- Title: same format as the issue title (keep the prefix). One PR = one issue.
- Body follows `.github/PULL_REQUEST_TEMPLATE.md` at the repository root exactly:
  - **Summary**: 3–5 bullets, what was done (PR-level summary, not a commit log)
  - **Closes #N**: auto-close trigger. Use the exact issue number.
  - **Test plan**: list the §Verification / §DoD items from the tasks file as bullet points
  - **Tasks**: link to the tasks file (all items checked)
- Target branch: `main`. Never open a PR against another phase branch.
- Present the draft body + a diff summary to the user → call `gh pr create` only after approval.

## Review rules

- On entering state F (PR open), first ask the user if they want a review. Get a yes/no.
- **yes** → auto-fetch PR diff and comments (`gh pr diff`, `gh pr view --comments`) → identify improvement candidates → present recommendation + reasoning → commit and push only the user-approved items.
- **no** → move immediately to state G (awaiting merge). Merge itself still requires separate user approval.
- Do not ignore external review comments — respond to each item (fix / push back / defer to a follow-up issue). Never close a PR with unanswered comments.
- Review cycles can repeat — if review → fix → re-review feels natural, stay in F instead of moving to G.

## Merge rules

- Merge strategy: **merge commit** only. Command: `gh pr merge <n> --merge --delete-branch --subject "<PR title> (#<n>)"`
- Never re-ask about squash/rebase. Only ask yes/no for the merge itself.
- `--delete-branch` auto-deletes the origin branch; gh CLI then checks out main, pulls, and deletes the local phase branch.

## Behavioral rules

- **GitHub actions (gh issue/pr create, merge, push) always require user approval.** Show the draft body and wait for yes/no.
- **Auto-update checkboxes**: when one item in the tasks file is done, immediately update `[ ]` → `[x]`. The user should not need to check manually.
- **Use the `commit` skill for commits**: in state D, follow the `commit` skill using the host's skill invocation mechanism, or read `.claude/skills/commit/SKILL.md` directly if unavailable. Include the tasks file checkbox update in the same commit.
- **One step at a time**: in state D, do not try to complete *all* unchecked items in one invocation. Stop at a natural work unit (typically 1–3 checkboxes = 1 commit) and report progress to the user.

## Report format

The first message after the skill is invoked must always follow this structure:

```
[Current state] <one of A–H, one-line summary>
[Next step] <concrete action>
[Approval needed] <yes/no if needed, otherwise "none">
```

Then proceed. Show a draft body or diff only when necessary, and wait for the user's response.
