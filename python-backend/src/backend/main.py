"""FastAPI 애플리케이션 진입점."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlalchemy import text

from backend.library.db import get_engine
from backend.library.logging_config import configure_logging
from backend.settings import get_settings

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings.debug_package)
    log.info("기동 — DB %s:%s/%s", settings.database.host, settings.database.port, settings.database.name)
    yield


app = FastAPI(title="주도주 매매 판단 보조 시스템", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/db")
def health_db() -> dict[str, str]:
    """DB 연결까지 확인한다. 기동 순서 문제를 헬스체크로 드러내기 위한 것."""
    with get_engine().connect() as conn:
        conn.execute(text("SELECT 1"))
    return {"status": "UP", "database": "UP"}
