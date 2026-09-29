"""FastAPI 애플리케이션 진입점."""

import logging
from contextlib import asynccontextmanager

from anyio.to_thread import current_default_thread_limiter
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator
from sqlalchemy import text
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.middleware.sessions import SessionMiddleware

from backend.library.db import get_engine
from backend.library.exception import BrokerTokenUnavailable, EntityNotFoundError
from backend.library import token_store
from backend.library.logging_config import bind_request, bind_user, configure_logging
from backend.library import metrics
from backend.library import tracing
from backend.library.scheduler import shutdown as shutdown_scheduler
from backend.library.scheduler import start as start_scheduler
from backend.auth.domain import SESSION_KEY, CurrentUser
from backend.auth.gate import is_public
from backend.auth.presentation import router as auth_router
from backend.feedback.presentation import router as feedback_router
from backend.insight.presentation import router as insight_router
from backend.overseasleadingstock.presentation import router as overseas_router
from backend.leadingstock.presentation import router as leading_router
from backend.leadercalendar.presentation import router as leader_calendar_router
from backend.leadertimeline.presentation import router as leader_timeline_router
from backend.market.presentation import router as market_router
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
    configure_logging(settings.debug_package, log_format=settings.log_format)
    log.info("기동 — DB %s:%s/%s", settings.database.host, settings.database.port, settings.database.name)
    # 브로커 토큰 저장소 — 재기동이 발급을 소비하지 않게 한다. 카탈로그 적재보다 먼저 와야
    # 한다(적재가 KIS를 쓴다). 실패해도 fail-soft라 기동을 막지 않는다.
    token_store.create_table()
    # 가입자·의견 테이블. DB가 잠깐 죽어도 기동은 막지 않는다 — token_store와 같은 fail-soft.
    # 실패하면 로그인 콜백과 의견 접수만 실패하고 공개 화면은 계속 뜬다.
    from backend.auth.domain import AppUser
    from backend.feedback.domain import Feedback
    from backend.leadercalendar.entities import LeaderDay, LeaderDayStock
    from backend.leadertimeline.entities import LeaderTick, LeaderTickStock
    from backend.leadingstock.infrastructure import StockMinuteCandleEntity
    from backend.overseasleadingstock.infrastructure import OverseasMinuteCandleEntity
    from backend.insight.entities import StockReason

    try:
        AppUser.__table__.create(get_engine(), checkfirst=True)
        Feedback.__table__.create(get_engine(), checkfirst=True)
        LeaderDay.__table__.create(get_engine(), checkfirst=True)
        LeaderDayStock.__table__.create(get_engine(), checkfirst=True)
        LeaderTick.__table__.create(get_engine(), checkfirst=True)
        LeaderTickStock.__table__.create(get_engine(), checkfirst=True)
        StockMinuteCandleEntity.__table__.create(get_engine(), checkfirst=True)
        OverseasMinuteCandleEntity.__table__.create(get_engine(), checkfirst=True)
        StockReason.__table__.create(get_engine(), checkfirst=True)
    except Exception:
        log.warning("가입자·의견·주도주 캘린더·타임라인·지난 날 분봉 테이블 생성 실패 — 그 기록 없이 동작한다", exc_info=True)
    load_stock_catalog()
    start_scheduler()
    # 스레드풀 계측. **여기여야 한다** — anyio 스레드풀은 실행 중인 이벤트 루프에 매여 있어
    # 모듈 로드 시점에는 아직 없다. lifespan은 루프 안이라 그 자리가 여기다.
    metrics.track_thread_pool(current_default_thread_limiter())
    yield
    shutdown_scheduler()


app = FastAPI(title="주도주 매매 판단 보조 시스템", lifespan=lifespan)

async def _require_login(request: Request, call_next):
    """`/api` 기본 차단. 허용목록(auth/gate.py)에 있는 경로만 연다.

    미들웨어로 두는 이유 — 엔드포인트마다 의존성을 붙이면 새로 추가할 때 빠뜨리기 쉽고,
    빠뜨린 쪽이 **열린 채로** 남는다. 여기서는 빠뜨리면 막히므로 사고가 노출이 아니라
    불편으로 끝난다. `/health`와 정적 경로는 `/api`가 아니라 애초에 대상이 아니다.
    """
    user = CurrentUser.from_session(request.session.get(SESSION_KEY))
    # 로그 컨텍스트도 여기서 심는다. 미들웨어를 하나 더 두면 같은 쿠키를 두 번 푸는 셈이고,
    # 관문이 이미 사용자를 손에 쥔 자리가 여기다.
    bind_user(user.id if user else None)

    path = request.url.path
    bind_request(request.method, path)
    if path.startswith("/api") and not is_public(path) and user is None:
        return _error("UNAUTHORIZED", 401)
    return await call_next(request)


# 미들웨어는 나중에 등록한 것이 바깥에 선다. 관문이 세션을 읽어야 하므로
# SessionMiddleware보다 **먼저** 등록해 안쪽에 오게 한다.
app.add_middleware(BaseHTTPMiddleware, dispatch=_require_login)

# 세션 쿠키 — authlib이 OAuth state·nonce를 여기 보관하므로 라우터보다 먼저 붙어야 한다.
# https_only는 운영 전제(부하 분산기가 TLS 종단). 로컬 http에서 로그인을 시험하려면 꺼야 한다.
_settings = get_settings()
app.add_middleware(
    SessionMiddleware,
    secret_key=_settings.session_secret,
    session_cookie="judozoo_session",
    https_only=True,
    same_site="lax",
)
app.state.google_redirect_uri = _settings.google.redirect_uri

# 메트릭 계측. **미들웨어 등록이 끝난 뒤**에 붙여야 한다 — 나중에 등록한 것이 바깥에 서므로
# 여기 와야 세션·인증 관문까지 포함한 전체 시간이 잡힌다.
#
# `/health`는 compose 헬스체크가 10초마다 때리는 경로다. 빼지 않으면 요청 히스토그램이
# 헬스체크로 뒤덮여 실제 트래픽이 안 보인다.
#
# **`/metrics`를 지키는 건 인증이 아니라 라우팅이다.** `_require_login`은 `/api`로 시작하는
# 경로만 막고, nginx는 `/api/`만 백엔드로 넘긴다. nginx에 `location /metrics`를 추가하는
# 순간 인터넷에 열린다 — 그 전제가 깨지지 않게 둔다.
Instrumentator(
    # 라우트에 매칭되지 않은 경로(봇의 /wp-login.php 난사)를 한 라벨로 묶는다.
    # 없으면 요청 경로 하나마다 시계열이 생겨 폭발한다.
    should_group_untemplated=True,
    excluded_handlers=["/health", "/health/db", "/metrics"],
).instrument(app).expose(app, include_in_schema=False)

# 트레이스. **lifespan이 아니라 여기여야 한다** — FastAPI 계측은 미들웨어를 더하므로
# 앱이 뜨기 전에 붙어야 하고, 그러려면 provider가 그보다 먼저 서 있어야 한다.
# lifespan에 두었더니 instrument_app이 "아직 안 켜졌다"고 보고 **조용히 건너뛰었다** —
# 잡·브로커 스팬만 오고 HTTP 서버 스팬이 0건이었다.
tracing.setup(_settings.otlp_endpoint, _settings.app_name, get_engine())

# 메트릭과 같은 이유로 미들웨어 조립이 끝난 뒤에 붙인다.
tracing.instrument_app(app)

app.include_router(auth_router)
app.include_router(feedback_router)
app.include_router(overseas_router)
app.include_router(stock_router)
app.include_router(market_router)
app.include_router(leading_router)
app.include_router(leader_calendar_router)
app.include_router(leader_timeline_router)
app.include_router(insight_router)


def _error(code: str, status: int) -> JSONResponse:
    """Kotlin `ApiResponse.error`와 같은 봉투 — 프론트가 이 모양을 기대한다."""
    return JSONResponse(status_code=status, content={"code": code, "status": status, "data": None})


@app.exception_handler(BrokerTokenUnavailable)
async def handle_broker_token_unavailable(request: Request, exc: BrokerTokenUnavailable) -> JSONResponse:
    """토큰 발급 백오프는 예상된 상태다 — 스택트레이스 없이 한 줄만 남긴다.

    화면이 5초마다 폴링하므로 트레이스를 찍으면 로그가 트레이스로 뒤덮인다.
    상태코드는 Kotlin과 같게 500으로 둔다.
    """
    log.warning("%s %s — %s", request.method, request.url.path, exc)
    return _error("INTERNAL_ERROR", 500)


@app.exception_handler(HTTPException)
async def handle_http_exception(request: Request, exc: HTTPException) -> JSONResponse:
    """인증 실패 등을 프론트가 읽는 봉투 모양으로 되돌린다. FastAPI 기본은 {"detail": ...}이라
    `apiFetch`가 code를 못 꺼낸다."""
    code = exc.detail if isinstance(exc.detail, str) else "ERROR"
    return _error(code, exc.status_code)


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
async def health() -> dict[str, str]:
    """**async여야 한다.** sync면 스레드풀을 쓰는데, 브로커 대기로 풀이 고갈되면
    헬스체크까지 같이 멈춘다. I/O가 없으니 이벤트 루프에서 바로 답하는 게 맞다."""
    return {"status": "UP"}


@app.get("/health/db")
def health_db() -> dict[str, str]:
    """DB 연결까지 확인한다. 기동 순서 문제를 헬스체크로 드러내기 위한 것."""
    with get_engine().connect() as conn:
        conn.execute(text("SELECT 1"))
    return {"status": "UP", "database": "UP"}
