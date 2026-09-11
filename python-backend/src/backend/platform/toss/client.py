"""토스 공통 — OAuth2 토큰 발급/캐시, 401 재시도.

Client Credentials Grant. 토큰 유효기간(보통 24h) 안에서 재사용하고 만료 5분 전에 재발급한다.
**client당 유효 토큰은 1개**라 같은 client_id로 다른 프로세스가 발급받으면 이쪽 토큰이 즉시 죽는다.
만료 시각은 아직 남아 있어 죽은 토큰을 계속 쓰게 되므로, 401을 보면 캐시를 버리고 한 번 다시 친다.
"""

import logging
import threading
from datetime import UTC, datetime, timedelta

import httpx

from backend.settings import get_settings

log = logging.getLogger(__name__)

_lock = threading.Lock()
_client: httpx.Client | None = None
_token: str | None = None
_token_expires_at = datetime.min.replace(tzinfo=UTC)

_EXPIRY_MARGIN_SECONDS = 300  # 만료 5분 전에 갱신
_MIN_LIFETIME_SECONDS = 60


def get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(base_url=get_settings().toss.base_url, timeout=30.0)
    return _client


def invalidate() -> None:
    global _token, _token_expires_at
    with _lock:
        _token = None
        _token_expires_at = datetime.min.replace(tzinfo=UTC)
        log.warning("Toss 토큰 캐시 무효화")


def get_access_token() -> str:
    global _token, _token_expires_at

    if _token is not None and datetime.now(UTC) < _token_expires_at:
        return _token

    with _lock:
        if _token is not None and datetime.now(UTC) < _token_expires_at:
            return _token

        settings = get_settings()
        log.info("Toss access token 발급 요청")
        response = get_client().post(
            "/oauth2/token",
            headers={"content-type": "application/x-www-form-urlencoded"},
            data={
                "grant_type": "client_credentials",
                # 시크릿 주입 시 끼어든 개행·공백을 흡수한다 — 남아 있으면 401 invalid_client로 거부된다.
                "client_id": settings.toss.client_id.strip(),
                "client_secret": settings.toss.client_secret.strip(),
            },
        )
        response.raise_for_status()
        body = response.json()

        token = body.get("access_token")
        if not token:
            raise RuntimeError("Toss 토큰 응답이 비어있음")

        lifetime = max(int(body.get("expires_in", 86400)) - _EXPIRY_MARGIN_SECONDS, _MIN_LIFETIME_SECONDS)
        _token = token
        _token_expires_at = datetime.now(UTC) + timedelta(seconds=lifetime)
        return token


def with_token_retry(call):
    """401이면 토큰 캐시를 버리고 1회 재시도. `call` 안에서 `get_access_token()`을 불러야 한다."""
    try:
        return call()
    except httpx.HTTPStatusError as e:
        if e.response.status_code != 401:
            raise
        log.warning("Toss 토큰 무효(401) 감지 — 캐시 무효화 후 1회 재시도")
        invalidate()
        return call()


def auth_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {get_access_token()}"}


def reset() -> None:
    """테스트 격리용."""
    global _client, _token, _token_expires_at
    if _client is not None:
        _client.close()
    _client = None
    _token = None
    _token_expires_at = datetime.min.replace(tzinfo=UTC)
