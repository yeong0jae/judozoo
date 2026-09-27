"""토스 캔들 — 국내 종목 당일 1분봉 (`/api/v1/candles`, MARKET_DATA_CHART 그룹 초당 20건).

키움 ka10080과 같은 봉이다 — 2026-09-23 삼성전자를 둘로 받아 겹친 692봉의 시고저종이 전부 일치했고
NXT 프리·애프터 봉도 같이 온다. 다른 점은 두 가지이고 여기서 키움 모양으로 맞춘다.

1. 시각이 봉의 **끝**이다(`[timestamp-1분, timestamp)`). 1분 당겨 키움 `cntr_tm`(봉 시작)과 맞춘다.
2. 체결 없는 분도 거래량 0 봉으로 채운다. **버린다** — 두면 스파이크의 직전 평균과
   이평이 키움 때와 달라진다.

한 번에 200봉까지라 하루치(최대 720봉)는 최신부터 거꾸로 여러 번 받는다.
"""

import logging
from datetime import datetime, timedelta
from decimal import Decimal

from backend.leadingstock.domain import MinuteCandle
from backend.library.rate_limiter import RateLimiter
from backend.library.time import KST, now, today
from backend.platform.toss import client
from backend.settings import get_settings

log = logging.getLogger(__name__)

_PAGE_SIZE = 200
#: 하루치(720봉)를 넘겨 받지 않게 막는 상한 — 응답이 이상해도 끝없이 돌지 않는다.
_MAX_PAGES = 5

_limiter: RateLimiter | None = None


def _get_limiter() -> RateLimiter:
    """캔들 그룹 전용. 휴장 조회(MARKET_DATA)와는 한도를 따로 센다."""
    global _limiter
    if _limiter is None:
        permits = get_settings().toss.chart_permits_per_second
        _limiter = RateLimiter(
            "toss-chart", permits_per_period=1, period_seconds=1.0 / permits, timeout_seconds=20.0
        )
    return _limiter


def fetch_today_minute_candles(stock_code: str, since: datetime | None = None) -> list[MinuteCandle]:
    """오늘 1분봉 중 `since` 이후(포함) 봉만, 시각 오름차순. `since`가 없으면 오늘치 전부.

    실패는 예외로 올린다 — 이어 붙이는 쪽이 기존 봉을 지킬지 스스로 정한다.
    """
    day = today()
    floor = since or datetime.combine(day, datetime.min.time())
    symbol = stock_code.split("_")[0]  # 랭킹 코드의 `_AL` — 토스 심볼에 `_`는 못 쓴다
    # 받을 만큼만 청한다. 증분이면 대개 몇 봉이라 한 번에 끝난다.
    count = _PAGE_SIZE if since is None else min(_PAGE_SIZE, int((now() - since).total_seconds() // 60) + 3)

    candles: dict[datetime, MinuteCandle] = {}
    before: str | None = None
    for _ in range(_MAX_PAGES):
        result = _page(symbol, max(count, 1), before)
        oldest: datetime | None = None
        for raw in result.get("candles") or []:
            candle = _to_candle(raw)
            oldest = candle.date_time if oldest is None else min(oldest, candle.date_time)
            if candle.date_time.date() == day and candle.date_time >= floor and candle.volume > 0:
                candles[candle.date_time] = candle
        before = result.get("nextBefore")
        if not before or oldest is None or oldest <= floor:
            break
        count = _PAGE_SIZE
    return [candles[t] for t in sorted(candles)]


def _page(symbol: str, count: int, before: str | None) -> dict:
    params = {"symbol": symbol, "interval": "1m", "count": count, "adjusted": "true"}
    if before:
        params["before"] = before

    def call() -> dict:
        _get_limiter().acquire()
        response = client.get_client().get("/api/v1/candles", params=params, headers=client.auth_headers())
        response.raise_for_status()
        return response.json().get("result") or {}

    return client.with_token_retry(call)


def _to_candle(raw: dict) -> MinuteCandle:
    # 봉 끝 시각 → 봉 시작 시각(키움과 같은 기준)
    at = datetime.fromisoformat(raw["timestamp"]).astimezone(KST).replace(tzinfo=None) - timedelta(minutes=1)
    close = int(Decimal(raw["closePrice"]))
    volume = int(Decimal(raw["volume"]))
    return MinuteCandle(
        date_time=at,
        open_price=int(Decimal(raw["openPrice"])),
        high_price=int(Decimal(raw["highPrice"])),
        low_price=int(Decimal(raw["lowPrice"])),
        close_price=close,
        volume=volume,
        trading_value=close * volume,  # 키움과 같은 근사 — 응답에 거래대금이 없다
    )


def reset() -> None:
    """테스트 격리용."""
    global _limiter
    _limiter = None
