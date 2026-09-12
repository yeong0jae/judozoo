"""KIS 공통 HTTP 인프라 — 공유 리미터, 액세스 토큰.

Kotlin의 `KisRestClientConfig` + `KisAuthClient`에 대응한다.
Spring의 빈 대신 모듈 수준 지연 초기화를 쓴다 — 프로세스당 하나면 충분하고,
DI 컨테이너를 흉내 낼 이유가 없다.
"""

import logging
import threading
from datetime import UTC, datetime, timedelta

import httpx

from backend.library import token_store
from backend.library.exception import BrokerTokenUnavailable
from backend.library.rate_limiter import RateLimiter
from backend.settings import get_settings

log = logging.getLogger(__name__)

PROVIDER = "KIS"

_lock = threading.Lock()
_client: httpx.Client | None = None
_limiter: RateLimiter | None = None
_token: str | None = None
_token_expires_at = datetime.min.replace(tzinfo=UTC)
_token_retry_after = datetime.min.replace(tzinfo=UTC)

# 발급이 거부된 뒤 다시 시도하기까지 기다리는 시간.
# KIS는 같은 앱키로 토큰을 짧은 간격에 재발급하면 거부한다. 요청마다 다시 두드리면
# 거부만 반복하면서 KIS에 불필요한 부하를 준다.
_TOKEN_RETRY_BACKOFF = timedelta(seconds=60)


class RateLimitedTransport(httpx.BaseTransport):
    """요청 직전에 허가를 받는다. Kotlin의 `ClientHttpRequestInterceptor`에 대응."""

    def __init__(self, limiter: RateLimiter, inner: httpx.BaseTransport) -> None:
        self._limiter = limiter
        self._inner = inner

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        self._limiter.acquire()
        return self._inner.handle_request(request)

    def close(self) -> None:
        self._inner.close()


def get_limiter() -> RateLimiter:
    """KIS 호출 공유 리미터.

    **1초에 N개를 한꺼번에 충전하지 않는다.** 그렇게 하면 1초 경계에서 이전·이후 창이
    KIS 윈도우에 겹쳐 최대 2N이 몰리고 EGW00201이 간헐 발생한다. (1000/N)ms마다
    1개씩 균등 발급해 버스트 자체를 없앤다 — Kotlin 쪽과 같은 구성이다.
    """
    global _limiter
    if _limiter is None:
        permits = get_settings().kis.query_permits_per_second
        _limiter = RateLimiter(
            "kis-query",
            permits_per_period=1,
            period_seconds=1.0 / permits,
            timeout_seconds=30.0,  # 페이징·동시요청이 큐잉돼도 거부 대신 대기
        )
    return _limiter


def get_client() -> httpx.Client:
    global _client
    if _client is None:
        settings = get_settings()
        _client = httpx.Client(
            base_url=settings.kis.base_url,
            transport=RateLimitedTransport(get_limiter(), httpx.HTTPTransport()),
            timeout=30.0,
        )
    return _client


class KisTokenUnavailable(BrokerTokenUnavailable):
    """직전 발급이 거부돼 백오프 중이다."""


def get_access_token() -> str:
    """23시간 캐시. 만료 전 재사용하고, 동시 요청은 한 번만 발급한다."""
    global _token, _token_expires_at, _token_retry_after

    if _token is not None and datetime.now(UTC) < _token_expires_at:
        return _token

    with _lock:
        if _token is not None and datetime.now(UTC) < _token_expires_at:
            return _token

        # 재기동 직후엔 메모리가 비어 있다. KIS는 앱키당 1분 1회라 **DB를 먼저 본다**.
        restored = token_store.load(PROVIDER)
        if restored is not None:
            _token, _token_expires_at = restored
            return _token

        now = datetime.now(UTC)
        if now < _token_retry_after:
            raise KisTokenUnavailable(
                f"토큰 발급 백오프 중 — {(_token_retry_after - now).seconds}초 후 재시도"
            )

        settings = get_settings()
        log.info("KIS access token 발급 요청")
        response = get_client().post(
            "/oauth2/tokenP",
            headers={"content-type": "application/json; charset=utf-8"},
            json={
                "grant_type": "client_credentials",
                "appkey": settings.kis.app_key,
                "appsecret": settings.kis.app_secret,
            },
        )
        if response.status_code != 200:
            # KIS는 같은 앱키로 토큰을 자주 재발급하면 거부한다(1분 간격 제한).
            # 본문에 사유 코드가 들어 있으므로 버리지 말고 남긴다.
            _token_retry_after = datetime.now(UTC) + _TOKEN_RETRY_BACKOFF
            log.error(
                "KIS token 발급 실패 status=%s body=%s — %s초 백오프",
                response.status_code,
                response.text,
                int(_TOKEN_RETRY_BACKOFF.total_seconds()),
            )
            response.raise_for_status()

        token = response.json().get("access_token")
        if not token:
            raise RuntimeError("KIS token 응답에 access_token 없음")

        _token = token
        _token_expires_at = datetime.now(UTC) + timedelta(hours=23)
        token_store.save(PROVIDER, token, _token_expires_at)
        log.info("KIS access token 발급 완료")
        return token


def auth_headers(tr_id: str) -> dict[str, str]:
    settings = get_settings()
    return {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {get_access_token()}",
        "appkey": settings.kis.app_key,
        "appsecret": settings.kis.app_secret,
        "tr_id": tr_id,
        "custtype": "P",
    }


def reset() -> None:
    """테스트 격리용. 토큰과 커넥션을 버린다."""
    global _client, _limiter, _token, _token_expires_at, _token_retry_after
    if _client is not None:
        _client.close()
    _client = None
    _limiter = None
    _token = None
    _token_expires_at = datetime.min.replace(tzinfo=UTC)
    _token_retry_after = datetime.min.replace(tzinfo=UTC)
