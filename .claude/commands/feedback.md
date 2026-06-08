---
description: 정산 중 발견된 코드 피드백 목록을 확인하고 처리한다
allowed-tools: Read, Write, Edit, Bash(git diff:*), Bash(git rev-parse:*)
---
`.claude/debt/feedback.md` 의 미처리 항목(`- [ ]`)을 확인하라.

!`cat .claude/debt/feedback.md 2>/dev/null || echo "(피드백 없음)"`

작업:
1. 미처리 항목이 없으면 "피드백 없음" 한 줄로 끝내라.
2. 있으면 목록을 보여주고 어떤 것부터 처리할지 사용자에게 물어라.
3. 사용자가 고른 항목을 처리한 뒤 `- [ ]` → `- [x]` 로 체크하라.
