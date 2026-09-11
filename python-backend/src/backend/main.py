"""FastAPI 애플리케이션 진입점."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import text

from backend.library.db import get_engine
from backend.library.exception import EntityNotFoundError
from backend.library.logging_config import configure_logging
from backend.library.scheduler import shutdown as shutdown_scheduler
from backend.library.scheduler import start as start_scheduler
from backend.news.presentation import router as news_router
from backend.overseasleadingstock.presentation import router as overseas_router
from backend.stock.presentation import router as stock_router
from backend.settings import get_settings

log = logging.getLogger(__name__)


def load_stock_catalog() -> None:
    """기동 시 종목 카탈로그 적재 — Kotlin `ApplicationReadyEvent`에 대응.

    내부가 fail-soft라 다운로드가 실패해도 DB의 직전 데이터로 떨어지고 기동은 계속된다.
    """
    from backend.stock.scheduler import refresh_catalog

    refresh_catalog()


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings.debug_package)
    log.info("기동 — DB %s:%s/%s", settings.database.host, settings.database.port, settings.database.name)
    load_stock_catalog()
    start_scheduler()
    yield
    shutdown_scheduler()


app = FastAPI(title="주도주 매매 판단 보조 시스템", lifespan=lifespan)
app.include_router(news_router)
app.include_router(overseas_router)
app.include_router(stock_router)


def _error(code: str, status: int) -> JSONResponse:
    """Kotlin `ApiResponse.error`와 같은 봉투 — 프론트가 이 모양을 기대한다."""
    return JSONResponse(status_code=status, content={"code": code, "status": status, "data": None})


@app.exception_handler(EntityNotFoundError)
async def handle_entity_not_found(request: Request, exc: EntityNotFoundError) -> JSONResponse:
    return _error("NOT_FOUND", 404)


@app.exception_handler(RequestValidationError)
async def handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    """FastAPI 기본은 422지만 기존 API는 400을 준다."""
    return _error("INVALID_PARAMETER", 400)


@app.exception_handler(Exception)
async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
    log.exception("Unhandled exception")
    return _error("INTERNAL_ERROR", 500)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/db")
def health_db() -> dict[str, str]:
    """DB 연결까지 확인한다. 기동 순서 문제를 헬스체크로 드러내기 위한 것."""
    with get_engine().connect() as conn:
        conn.execute(text("SELECT 1"))
    return {"status": "UP", "database": "UP"}
