---
name: commit
description: Conventional Commits 스타일 한국어 커밋 메시지 생성 및 커밋 실행
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*)
---

# commit

## 워크플로우

1. `git status`, `git diff --staged`를 병렬 실행해 변경사항 파악
2. 변경 의도를 분석해 타입 결정
3. 메시지 작성 후 사용자에게 보여주고 확인
4. 승인 시 커밋

## 타입

`feat` `fix` `refactor` `test` `docs` `chore`

## 메시지 규칙

- 형식: `<type>: <subject>`
- 한국어, 현재형 서술 ("추가", "수정"), 마침표 없음, 50자 내외
- 본문은 "왜"를 설명할 필요가 있을 때만 작성 (제목과 빈 줄로 구분)
- `Co-Authored-By` 및 생성 도구 **포함하지 않음**

## 커밋 실행

```bash
git commit -m "$(cat <<'EOF'
<제목>

<본문 (있을 경우)>
EOF
)"
```

민감 파일(`.env`, 키 파일 등)은 사용자 확인 없이 추가 금지.
