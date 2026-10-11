# --- build stage ---
FROM python:3.13-slim AS build
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
WORKDIR /app

# 의존성 캐싱: 매니페스트 먼저 복사해 소스 변경 시 재설치를 피한다.
COPY pyproject.toml uv.lock* ./
RUN uv sync --no-dev --no-install-project

COPY src ./src
RUN uv sync --no-dev

# --- runtime stage ---
FROM python:3.13-slim AS runtime
WORKDIR /app
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

COPY --from=build /app/.venv ./.venv
COPY src ./src
# 배포 스크립트가 컨테이너 교체 직전에 `alembic upgrade head`를 이 이미지로 돌린다.
COPY alembic.ini ./
COPY migrations ./migrations

EXPOSE 8000
CMD ["uvicorn", "backend.main:app", "--host", "0.0.0.0", "--port", "8000", "--app-dir", "src"]
