---
description: 이해부채 항목을 빠르게 기록한다 (작업 흐름 중단 최소화)
argument-hint: <모르겠는 것 한 줄>
allowed-tools: Bash(git rev-parse:*), Bash(git branch:*), Read, Write, Edit
---
지금 작업 흐름을 끊지 말고, 아래 항목을 이해부채 원장에 한 줄로만 추가하라.

기록할 항목: $ARGUMENTS

현재 git 컨텍스트:
- HEAD: !`git rev-parse --short HEAD 2>/dev/null || echo no-git`
- branch: !`git branch --show-current 2>/dev/null || echo -`

작업:
1. `.claude/debt/pending.md` 가 없으면 헤더와 함께 생성한다.
2. `## 미정산` 섹션 아래에 다음 형식으로 **한 줄** 추가한다:
   `- [ ] $ARGUMENTS — @<branch>/<HEAD> — added <오늘 YYYY-MM-DD>`
3. 추가했다는 사실만 한 문장으로 확인하라. **개념 설명은 하지 마라** — 설명은 /settle 담당이다.
