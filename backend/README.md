# backend

주도주 매매 판단 보조 시스템 백엔드. FastAPI + SQLAlchemy, 패키지는 `src/backend/`.

구조와 계층 규칙은 [`.claude/rules/architecture.md`](../.claude/rules/architecture.md),
기술 명세는 [`docs/spec.md`](../docs/spec.md)에 있다.

## 로컬 실행

```bash
uv sync                                    # 의존성 설치
uv run uvicorn backend.main:app --app-dir src --reload --port 8000
```

DB 접속 정보 등은 `secrets/.env`(리포 루트)에서 읽는다.

## 테스트

```bash
uv run pytest                        # 전체 (Testcontainers용 Docker 필요)
uv run pytest -m "not integration"   # Docker 없이
```

`tests/`는 `src/backend/`와 1:1로 대응한다. 규칙은 [`.claude/rules/testing.md`](../.claude/rules/testing.md).

## Docker

리포 루트의 `docker-compose.yml`에 `backend` 서비스로 들어 있다.

```bash
docker compose up --build backend
```

프로덕션은 Artifact Registry의 사전 빌드 이미지를 쓴다 (`docker-compose.prod.yml`).
