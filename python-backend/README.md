# python-backend

Kotlin/Spring 백엔드의 Python 이관 작업 공간. 계획은 [`docs/tasks/tasks-015.md`](../docs/tasks/tasks-015.md).

## 로컬 실행

```bash
uv sync                                    # 의존성 설치
uv run uvicorn backend.main:app --app-dir src --reload --port 8000
```

## 테스트

```bash
uv run pytest                    # 전체 (Docker 필요)
uv run pytest -m "not integration"   # Testcontainers 제외
```

## Docker

`docker-compose.yml`에 `python` 프로필로 들어 있다. 프로필을 주지 않으면 뜨지 않는다 —
프로덕션 VM에는 이 디렉터리 소스가 없어서 build가 실패하기 때문이다.

```bash
docker compose --profile python up python-backend
```

## 응답 동등성 비교

```bash
uv run python scripts/compare_responses.py /api/news/stock/005930
```
