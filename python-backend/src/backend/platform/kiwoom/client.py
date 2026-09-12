"""키움 공통 HTTP 인프라 — 조회 공유 리미터, 액세스 토큰.

Kotlin `KiwoomRestClientConfig` + `KiwoomQueryRateLimitInterceptor` + `KiwoomAuthClient`에 대응.
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

PROVIDER = "KIWOOM"

API_ID_HEADER = "api-id"
_QUERY_PREFIX = "ka"  # 조회 TR. 주문/계좌(kt*)는 별도 한도라 리미터를 태우지 않는다.

# 토큰 무효 — 응답 return_code 8005. 외부에서 토큰이 끊기면 이 코드로 돌아온다.
TOKEN_INVALID_CODE = 8005

# 발급이 거부된 뒤 다시 시도하기까지 기다리는 시간.
# 없으면 실패할 때마다 매 호출이 토큰을 다시 요청한다 — 실측 11분에 185회를 두드렸다.
# 거부만 반복하면서 키움에 불필요한 부하를 주고, 차단이라면 풀릴 기회도 없어진다.
# (KIS 쪽에 이미 같은 장치가 있다 — `platform/kis/client.py`)
_TOKEN_RETRY_BACKOFF = timedelta(seconds=60)

_lock = threading.Lock()
_client: httpx.Client | None = None
_limiter: RateLimiter | None = None
_token: str | None = None
_token_expires_at = datetime.min.replace(tzinfo=UTC)
_token_retry_after = datetime.min.replace(tzinfo=UTC)


class QueryRateLimitedTransport(httpx.BaseTransport):
    """조회 TR에만 허가를 요구한다 — Kotlin 인터셉터와 같은 분기."""

    def __init__(self, limiter: RateLimiter, inner: httpx.BaseTransport) -> None:
        self._limiter = limiter
        self._inner = inner

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        api_id = request.headers.get(API_ID_HEADER, "")
        if api_id.startswith(_QUERY_PREFIX):
            self._limiter.acquire()
        return self._inner.handle_request(request)

    def close(self) -> None:
        self._inner.close()


def get_limiter() -> RateLimiter:
    """조회 "초당 5건"을 모든 조회 호출에 한 버킷으로 적용.

    KIS와 같은 이유로 1초에 N개를 한꺼번에 충전하지 않고 (1000/N)ms마다 1개씩 균등 발급한다.
    폴러·돌파·스파이크가 같은 종목 분봉을 몰아 부를 때의 버스트를 여기서 평탄화한다.
    """
    global _limiter
    if _limiter is None:
        permits = get_settings().kiwoom.query_permits_per_second
        _limiter = RateLimiter(
            "kiwoom-query",
            permits_per_period=1,
            period_seconds=1.0 / permits,
            timeout_seconds=20.0,  # 버스트 시 거부 대신 대기
        )
    return _limiter


def get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(
            base_url=get_settings().kiwoom.base_url,
            transport=QueryRateLimitedTransport(get_limiter(), httpx.HTTPTransport()),
            timeout=30.0,
        )
    return _client


class KiwoomTokenUnavailable(BrokerTokenUnavailable):
    """직전 발급이 거부돼 백오프 중이다."""


def invalidate() -> None:
    """응답에서 토큰 무효(8005)가 확인됐을 때. 다음 `get_access_token()`이 새로 발급한다.

    8005는 "토큰이 외부에서 무효화됐다"는 뜻이라 즉시 재발급이 맞다 —
    발급 거부(백오프)와는 다른 상황이므로 백오프를 걸지 않는다.
    """
    global _token, _token_expires_at
    with _lock:
        _token = None
        _token_expires_at = datetime.min.replace(tzinfo=UTC)
        # DB에 남겨두면 재기동 시 죽은 토큰을 되살린다.
        token_store.clear(PROVIDER)
        log.warning("Kiwoom 토큰 캐시 무효화")


def get_access_token() -> str:
    """23시간 캐시(유효기간 24시간에서 1시간 여유). 동시 요청은 한 번만 발급한다."""
    global _token, _token_expires_at, _token_retry_after

    if _token is not None and datetime.now(UTC) < _token_expires_at:
        return _token

    with _lock:
        if _token is not None and datetime.now(UTC) < _token_expires_at:
            return _token

        # 재기동 직후엔 메모리가 비어 있다. 발급은 희소하므로 **DB를 먼저 본다**.
        restored = token_store.load(PROVIDER)
        if restored is not None:
            _token, _token_expires_at = restored
            return _token

        remaining = _token_retry_after - datetime.now(UTC)
        if remaining.total_seconds() > 0:
            raise KiwoomTokenUnavailable(
                f"토큰 발급 백오프 중 — {int(remaining.total_seconds())}초 후 재시도"
            )

        settings = get_settings()
        log.info("Kiwoom access token 발급 요청")
        response = get_client().post(
            "/oauth2/token",
            headers={"content-type": "application/json;charset=UTF-8"},
            json={
                "grant_type": "client_credentials",
                "appkey": settings.kiwoom.app_key,
                "secretkey": settings.kiwoom.app_secret,
            },
        )
        if response.status_code != 200:
            # 거부 사유가 본문·리다이렉트에 들어오므로 버리지 않고 남긴다.
            # (키움은 거부 시 start.html로 302를 주기도 한다 — 상태코드만 보면 원인을 놓친다)
            _token_retry_after = datetime.now(UTC) + _TOKEN_RETRY_BACKOFF
            log.error(
                "Kiwoom token 발급 실패 status=%s location=%s body=%s — %d초 백오프",
                response.status_code,
                response.headers.get("location", "-"),
                response.text[:200],
                int(_TOKEN_RETRY_BACKOFF.total_seconds()),
            )
            response.raise_for_status()

        body = response.json()
        code = body.get("return_code")
        if code is not None and code != 0:
            _token_retry_after = datetime.now(UTC) + _TOKEN_RETRY_BACKOFF
            log.error("Kiwoom 인증 실패 code=%s msg=%s", code, body.get("return_msg"))
            raise RuntimeError(f"Kiwoom API error: {body.get('return_msg')}")

        token = body.get("token") or body.get("access_token")
        if not token:
            _token_retry_after = datetime.now(UTC) + _TOKEN_RETRY_BACKOFF
            raise RuntimeError("Kiwoom token 응답에 토큰 없음")

        _token = token
        _token_expires_at = datetime.now(UTC) + timedelta(hours=23)
        token_store.save(PROVIDER, token, _token_expires_at)
        log.info("Kiwoom access token 발급 완료 — expires_dt=%s", body.get("expires_dt", "N/A"))
        return token


def parse_signed_float(value: str | None) -> float:
    """키움 부호 변종을 모두 흡수한다 — `"+1.23"`, `"1.23-"`(후위 부호), `"-1.23"`.

    후위 부호가 진짜로 온다. 그냥 float()에 넣으면 0으로 떨어져 등락률이 조용히 사라진다.
    """
    s = (value or "").strip()
    if not s:
        return 0.0
    if s.startswith("+"):
        s = s[1:]
    elif s.endswith("-"):
        s = "-" + s[:-1]
    try:
        return float(s)
    except ValueError:
        return 0.0


def parse_signed_int(value: str | None) -> int:
    """부호 변종을 흡수해 정수로. 순매수처럼 **부호가 의미를 갖는** 값에 쓴다."""
    s = (value or "").strip()
    if not s:
        return 0
    if s.startswith("+"):
        s = s[1:]
    elif s.endswith("-"):
        s = "-" + s[:-1]
    try:
        return int(s)
    except ValueError:
        return 0


def query_headers(api_id: str, cont_yn: str = "N", next_key: str = "") -> dict[str, str]:
    """조회 TR 공통 헤더. `api-id`가 `ka`로 시작해야 리미터를 탄다."""
    return {
        "content-type": "application/json;charset=UTF-8",
        "authorization": f"Bearer {get_access_token()}",
        API_ID_HEADER: api_id,
        "cont-yn": cont_yn,
        "next-key": next_key,
    }


def reset() -> None:
    """테스트 격리용."""
    global _client, _limiter, _token, _token_expires_at, _token_retry_after
    if _client is not None:
        _client.close()
    _client = None
    _limiter = None
    _token = None
    _token_expires_at = datetime.min.replace(tzinfo=UTC)
    _token_retry_after = datetime.min.replace(tzinfo=UTC)
