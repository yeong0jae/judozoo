"""야후 파이낸스 chart API — 시세 요약(meta) + 캔들(timestamp/quote)을 한 응답으로 준다.

인증 없는 공개 엔드포인트다. KIS는 CME 시세가 유료라 나스닥 선물을 못 받아, 그 대체 소스로 쓴다.
나스닥 선물은 심볼 `NQ=F`만 넘기면 야후가 근월물을 알아서 물려준다(코드 조립 불필요).

시각은 epoch 초로 오므로 KST로 변환해 넘긴다. 실패 시 None / 빈 리스트.
"""

import logging
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

import httpx

log = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")
_BASE_URL = "https://query1.finance.yahoo.com"

_client: httpx.Client | None = None


@dataclass(frozen=True)
class YahooQuote:
    name: str
    price: float
    prev_close: float


@dataclass(frozen=True)
class YahooBar:
    date: str  # yyyy-MM-dd (KST)
    time: str  # HH:mm:ss (KST)
    open: float
    high: float
    low: float
    close: float
    volume: float


def get_client() -> httpx.Client:
    """User-Agent가 없으면 야후가 차단한다. 반드시 붙인다."""
    global _client
    if _client is None:
        _client = httpx.Client(
            base_url=_BASE_URL,
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=30.0,
        )
    return _client


def fetch_quote(symbol: str) -> YahooQuote | None:
    """[symbol] 시세 요약 — 현재가·전일종가(직전 세션 마감가). 등락은 호출측이 계산한다.

    `chartPreviousClose`는 '조회 창 직전의 종가'라 range에 따라 값이 달라진다.
    그래서 `previousClose`를 우선한다.
    """
    result = _fetch(symbol, "1m", "1d")
    meta = (result or {}).get("meta") or {}
    price = meta.get("regularMarketPrice")
    prev_close = meta.get("previousClose")
    if prev_close is None:
        prev_close = meta.get("chartPreviousClose")
    if price is None or prev_close is None:
        return None
    return YahooQuote(name=meta.get("shortName") or "", price=price, prev_close=prev_close)


def fetch_extended_quote(symbol: str) -> YahooQuote | None:
    """[symbol] 시세 — 프리·애프터마켓 체결까지 반영한다(관심 종목 시세용).

    meta는 정규장 값만 담는다. 프리마켓 중엔 `regularMarketPrice`가 '직전 정규장 종가'에
    멈춰 있고 `previousClose`는 한 세션 더 과거를 가리킨다. 그래서 확장시간엔
    마지막 캔들을 현재가로, 직전 정규장 종가를 기준가로 쓴다. 정규장 중엔 meta를 그대로 쓴다.
    """
    result = _fetch(symbol, "1m", "1d", include_pre_post=True)
    if result is None:
        return None
    meta = result.get("meta") or {}
    regular_price = meta.get("regularMarketPrice")
    if regular_price is None:
        return None

    last_bar = _last_close(result)
    regular_time = meta.get("regularMarketTime")
    extended = last_bar is not None and regular_time is not None and last_bar[0] > regular_time

    if extended:
        return YahooQuote(
            name=meta.get("shortName") or "",
            price=last_bar[1],
            prev_close=regular_price,
        )

    prev_close = meta.get("previousClose")
    if prev_close is None:
        prev_close = meta.get("chartPreviousClose")
    if prev_close is None:
        return None
    return YahooQuote(name=meta.get("shortName") or "", price=regular_price, prev_close=prev_close)


def fetch_candles(symbol: str, interval: str, range_: str) -> list[YahooBar]:
    """[symbol] 캔들 — [interval]("1m"/"1d"), [range_]("1d","5d","6mo" 등).

    거래가 없던 분은 값이 null로 오므로 버린다.
    세션 마감 봉은 거래량 0짜리 중복으로 한 번 더 오므로 **같은 시각은 뒤엣것으로 덮는다**
    (차트는 시각이 유일해야 한다). 시각 오름차순.
    """
    result = _fetch(symbol, interval, range_)
    if result is None:
        return []
    times = result.get("timestamp")
    quote = _first_quote(result)
    if not times or quote is None:
        return []

    by_time: dict[str, YahooBar] = {}
    for i, epoch in enumerate(times):
        close = _at(quote.get("close"), i)
        if close is None:
            continue
        at = datetime.fromtimestamp(epoch, KST)
        bar = YahooBar(
            date=at.strftime("%Y-%m-%d"),
            time=at.strftime("%H:%M:%S"),
            open=_at(quote.get("open"), i, close),
            high=_at(quote.get("high"), i, close),
            low=_at(quote.get("low"), i, close),
            close=close,
            volume=float(_at(quote.get("volume"), i, 0) or 0),
        )
        by_time[f"{bar.date} {bar.time}"] = bar

    # 키가 "yyyy-MM-dd HH:mm:ss"라 사전순 정렬이 곧 시각 오름차순이다.
    return [by_time[k] for k in sorted(by_time)]


def _last_close(result: dict[str, Any]) -> tuple[int, float] | None:
    """마지막으로 체결된 캔들(거래 없는 분은 close가 null로 온다)."""
    times = result.get("timestamp")
    quote = _first_quote(result)
    if not times or quote is None:
        return None
    closes = quote.get("close") or []
    for i in reversed(range(len(times))):
        close = _at(closes, i)
        if close is not None:
            return times[i], close
    return None


def _first_quote(result: dict[str, Any]) -> dict[str, Any] | None:
    quotes = ((result.get("indicators") or {}).get("quote")) or []
    return quotes[0] if quotes else None


def _at(values: list | None, index: int, default: Any = None) -> Any:
    if not values or index >= len(values):
        return default
    value = values[index]
    return default if value is None else value


def _fetch(
    symbol: str,
    interval: str,
    range_: str,
    include_pre_post: bool = False,
) -> dict[str, Any] | None:
    params: dict[str, str] = {"interval": interval, "range": range_}
    if include_pre_post:
        params["includePrePost"] = "true"
    try:
        response = get_client().get(f"/v8/finance/chart/{symbol}", params=params)
        response.raise_for_status()
        chart = response.json().get("chart") or {}
        if chart.get("error"):
            log.error("야후 차트 오류: %s (symbol=%s)", chart["error"], symbol)
            return None
        results = chart.get("result") or []
        return results[0] if results else None
    except Exception:
        log.exception("야후 차트 조회 실패 (symbol=%s, interval=%s)", symbol, interval)
        return None


def reset() -> None:
    """테스트 격리용."""
    global _client
    if _client is not None:
        _client.close()
    _client = None
