#!/usr/bin/env bash
# SessionStart hook: 세션 시작 시 이해부채 잔액과 대기 피드백을 컨텍스트로 주입.
# SessionStart에서는 plain stdout이 그대로 Claude 컨텍스트에 들어간다.
DEBT="${CLAUDE_PROJECT_DIR}/.claude/debt/pending.md"
FEEDBACK="${CLAUDE_PROJECT_DIR}/.claude/debt/feedback.md"

open=0
if [ -f "$DEBT" ]; then open=$(grep -c '^- \[ \]' "$DEBT" 2>/dev/null || true); fi
echo "[이해부채] 미정산 ${open:-0}건 — 목록: .claude/debt/pending.md"

if [ "${open:-0}" -ge 8 ]; then
  echo "[이해부채] 잔액 누적됨. 새 탭에서 /settle 로 정산을 권장한다."
fi

if [ -f "$FEEDBACK" ] && [ -s "$FEEDBACK" ]; then
  fb=$(grep -c '^- \[ \]' "$FEEDBACK" 2>/dev/null || true)
  if [ "${fb:-0}" -gt 0 ]; then
    echo "[피드백] 정산 중 발견된 코드 개선 ${fb}건 대기 — .claude/debt/feedback.md (우선 처리 후보)"
  fi
fi
exit 0
