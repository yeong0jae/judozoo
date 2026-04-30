---
name: workflow
description: 개발 워크플로우를 현재 상태 자동 추론 후 다음 단계로 진행
disable-model-invocation: true
---

# workflow

phase별 개발 워크플로우 오케스트레이터. 현재 git/GitHub 상태에서 어느 단계에 있는지 추론한 뒤 사용자와 함께 단계별로 진행한다.

## 상태 추론 (skill 호출 시 즉시 실행)

```bash
LATEST=$(ls docs/tasks/tasks-*.md 2>/dev/null | sort | tail -1)
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

수집한 신호를 다음 표로 매핑:

| 상태 | 신호 | 다음 단계 |
|------|------|----------|
| **A. tasks 없음** | 마지막 `tasks-*.md`의 항목이 모두 체크됨 | → plan.md를 참고하여 다음 phase의 tasks-NNN.md 작성 |
| **B. 이슈 미생성** | 체크해야할 tasks가 있지만 해당 tasks의 open issue 없음, 현재 브랜치 main | → 이슈 초안 작성 → 사용자 승인 → `gh issue create` |
| **C. 브랜치 미생성** | 이슈 있음, 현재 브랜치 main | → 브랜치명 제안 (`<type>/#<n>-<slug>`) → 사용자 승인 → `git checkout -b <name>` |
| **D. 구현 중** | 작업 브랜치(`<type>/#<n>-...`), 미체크 항목 ≥ 1 | → tasks-NNN.md의 *다음 미체크 항목* 1개 진행 → 완료 시 자동으로 `[ ]` → `[x]` Edit + `/commit` 스킬로 커밋 |
| **E. PR 미생성** | 작업 브랜치, 모든 항목 체크됨, open PR 없음 | → PR 본문 초안 → 사용자 승인 → `gh pr create` |
| **F. 리뷰 진행 중** | PR open된 상태 | → 사용자에게 리뷰 의사 질문 -> 의사 있으면 해당 pr에서 변경 사항을 조회하여 개선점 파악 -> 선택지 제안, 추천 선택지와 이유 설명 -> 사용자 승인 후 반영 |
| **G. merge 대기** | PR open된 상태 | → 사용자에게 리뷰 의사 질문 -> 없으면 merge 사용자 승인 -> merge |
| **H. merge 완료** | PR 닫힘(merged), 현재 브랜치 정리 안 됨 | → main 체크아웃 + pull + phase 브랜치 정리(local). 다음 phase tasks 작성 제안 |

## tasks 작성 원칙

- 1 phase = 1 tasks 파일 (`tasks-NNN.md`). plan.md의 phase와 1:1 매핑.
- 모든 작업 항목은 체크박스(`- [ ]`)로 표현. 체크 단위 = "한 번에 끝나는 자연스러운 작업 1건" (보통 1~3개 = 1 커밋)

## 이슈 생성 원칙

- B 상태(tasks 있음 + open issue 없음)에서만 생성. tasks 작성 전 이슈를 만들지 않는다.
- 작업 성격에 맞는 템플릿 1개 선택: `feature` (새 기능 / phase 작업) / `fix` (버그) / `refactor` (구조 개선) / `chore` (환경·도구).
- 제목은 템플릿 prefix(`feat: ` / `fix: ` / `refactor: ` / `chore: `)를 그대로 사용 + 한 줄 요약.
- 본문은 [.github/ISSUE_TEMPLATE/](../../.github/ISSUE_TEMPLATE/) 양식의 섹션 구조를 그대로 따름.
- 제출 직후 응답에서 이슈 번호 추출 → C 상태(브랜치 생성)에 그대로 입력.

## 브랜치 생성 원칙

- 작업 단위: `docs/tasks/tasks-NNN.md` (NNN = 0-base phase 번호 zero-padding)
- 브랜치: `<type>/#<issue-number>-<slug>`
  - 예: `feat/#1-bootstrap`, `chore/#5-ddl-rollout`
  - 타입 기본값: `feat` `fix` `refactor` `test` `docs` `chore`
  - 이슈 번호는 B 단계에서 `gh issue create` 직후 응답에서 추출 (`gh issue view --json number`)
  - 브랜치명에 `#`가 포함되므로 `git checkout -b "<name>"`처럼 따옴표 필수 (셸 주석 해석 방지)
- 기반 main 브랜치: `main`

## PR 생성 원칙

- E 상태(모든 체크박스 완료 + open PR 없음)에서만 생성. 미체크 항목 남은 채로 PR을 만들지 않는다.
- 제목: 이슈 제목과 동일 형식(prefix 유지). 한 PR = 한 이슈.
- 본문은 [.github/PULL_REQUEST_TEMPLATE.md](../../.github/PULL_REQUEST_TEMPLATE.md) 양식 그대로:
  - **Summary**: 3~5 bullet, 무엇을 했는지 (커밋 로그가 아니라 PR 단위 요약)
  - **Closes #N**: 자동 close 트리거. 이슈 번호 정확히
  - **Test plan**: tasks-NNN.md의 §검증 / §DoD 항목을 체크박스로 옮김 (PR 단계에서 직접 검증)
  - **Tasks**: tasks-NNN.md 링크 (모든 항목 체크 완료 상태)
- 대상 브랜치: `main`. 다른 phase 브랜치로 PR 만들지 않는다.
- 본문 초안 + 변경 diff 요약을 사용자에게 제시 → 승인 후 `gh pr create` 호출.

## 리뷰 진행 원칙

- F 상태(PR open) 진입 시 가장 먼저 사용자에게 **리뷰 의사 질문**. yes/no 받는다.
- **yes** → PR diff와 코멘트 자동 조회(`gh pr diff`, `gh pr view --comments`) → 개선 후보 정리 → 추천 + 이유 설명 → 사용자 승인 받은 항목만 수정 커밋 → push.
- **no** → G 상태(merge 대기)로 즉시 이행. merge 자체는 별도 사용자 승인 필요.
- 외부 리뷰 코멘트가 있으면 무시하지 않고 항목별로 응답 (수정 / 반박 / 후속 이슈로 위임 중 하나). 응답 없는 코멘트로 PR을 닫지 않는다.
- 리뷰 사이클은 반복 가능 — 리뷰 → 수정 → 재리뷰가 자연스러우면 G 상태로 가지 않고 F를 다시 돈다.

## 머지 원칙

- 머지 방식: **merge commit** 고정. 명령: `gh pr merge <n> --merge --delete-branch --subject "<PR 제목> (#<n>)"`
- 옵션(squash/rebase) 재질문 금지. 머지 자체에 대한 yes/no 승인만 받는다.
- `--delete-branch`로 origin 브랜치 자동 삭제 + gh CLI가 머지 후 main 체크아웃 + pull + 로컬 phase 브랜치 삭제까지 자동 수행.


## 행동 원칙

- **GitHub 액션(gh issue/pr create, merge, push)은 항상 사용자 승인 후 실행**. 본문 초안을 보여주고 yes/no를 받는다.
- **체크박스 자동 갱신**: tasks-NNN.md의 항목 1개를 완료하면 즉시 `[ ]` → `[x]` Edit. 사용자가 손으로 체크할 필요 없음.
- **커밋은 `/commit` 스킬 사용**: D 상태에서 작업 단위 완료 후 커밋할 때는 `git commit` 직접 실행 대신 `/commit` 슬래시 커맨드(Skill 도구)를 호출한다. tasks-NNN.md 체크박스 갱신도 커밋에 포함시킨다.
- **"다음 한 단계만"**: 한 번 호출에 D 상태에서 *모든* 미체크 항목을 다 끝내려 들지 말 것. 자연스러운 작업 단위(보통 1~3개 체크박스 = 1 커밋)에서 멈추고 사용자에게 진척 보고.

## 사용자에게 보고하는 형식

skill 호출 후 첫 메시지는 항상 다음 구조로:

```
[현재 상태] <A~H 중 하나, 한 줄 요약>
[다음 단계] <구체 액션>
[필요한 승인] <있으면 yes/no, 없으면 "없음">
```

그 후 단계를 진행. 필요한 경우만 본문 초안/diff를 보여주고 사용자 응답을 기다림.
