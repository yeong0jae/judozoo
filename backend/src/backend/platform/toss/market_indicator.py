"""토스 Market Indicators — 투자자별 매매대금 + 캔들(OHLCV), 코스피/코스닥.

투자자별은 매수/매도 거래대금(원)을 주므로 **순매수 = 매수 − 매도**로 계산해 억원으로 환산한다.
기관은 7개 세부(연기금·투신·금융투자·사모·보험·은행·기타금융)를 함께 준다.
캔들은 실제 OHLCV라 키움 지수값 합성과 달리 정직한 시가·고가·저가를 준다.
"""

import logging
import math
from dataclasses import dataclass
from datetime import date, datetime

from backend.library.rate_limiter import RateLimiter
from backend.platform.toss import client
from backend.settings import get_settings

log = logging.getLogger(__name__)

_EOK = 100_000_000.0

_BREAKDOWN_FIELDS = (
    ("pension_fund_eok", "pensionFund"),
    ("trust_eok", "trust"),
    ("financial_investment_eok", "financialInvestment"),
    ("private_equity_eok", "privateEquityFund"),
    ("insurance_eok", "insurance"),
    ("bank_eok", "bank"),
    ("other_finance_eok", "otherFinancialInstitution"),
)


@dataclass(frozen=True)
class InstitutionBreakdown:
    pension_fund_eok: int
    trust_eok: int
    financial_investment_eok: int
    private_equity_eok: int
    insurance_eok: int
    bank_eok: int
    other_finance_eok: int


@dataclass(frozen=True)
class MarketInvestorRecord:
    date: date
    source_updated_at: datetime | None
    individual_net_eok: int
    foreign_net_eok: int
    institution_net_eok: int
    other_corp_net_eok: int
    breakdown: InstitutionBreakdown


@dataclass(frozen=True)
class TossCandle:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class CandlesPage:
    candles: list[TossCandle]
    next_before: str | None
    """다음 페이지 커서(직전 응답 값 그대로) — 더 이전 페이지가 없으면 None."""


def net_eok(amount: dict | None) -> int:
    """순매수(억) = (매수 − 매도) / 1억.

    Kotlin `roundToLong()`은 `floor(x + 0.5)`라 .5를 항상 위로 보낸다.
    Python `round()`는 짝수로 보내는 은행가 반올림이라 값이 갈린다 — 그래서 직접 구현한다.
    """
    if not amount:
        return 0
    buy = _to_float(amount.get("buyAmount"))
    sell = _to_float(amount.get("sellAmount"))
    return math.floor((buy - sell) / _EOK + 0.5)


def fetch_investor_trading(
    symbol: str, interval: str = "1d", count: int = 10, until: date | None = None
) -> list[MarketInvestorRecord]:
    """`symbol` "KOSPI"/"KOSDAQ". 최신순 반환. 실패 시 빈 목록."""
    try:
        return client.with_token_retry(lambda: _fetch_investor(symbol, interval, count, until))
    except Exception:
        log.error("Toss 투자자별 매매대금 조회 실패 symbol=%s interval=%s", symbol, interval, exc_info=True)
        return []


def _fetch_investor(symbol: str, interval: str, count: int, until: date | None) -> list[MarketInvestorRecord]:
    params: dict[str, str | int] = {"interval": interval, "count": count}
    if until is not None:
        params["until"] = until.isoformat()

    response = client.get_client().get(
        f"/api/v1/market-indicators/{symbol}/investor-trading",
        params=params,
        headers=client.auth_headers(),
    )
    response.raise_for_status()
    records = ((response.json().get("result") or {}).get("records")) or []
    return [r for r in (_to_record(item) for item in records) if r is not None]


def _to_record(item: dict) -> MarketInvestorRecord | None:
    on = _parse_date(item.get("date"))
    if on is None:
        return None
    institution = item.get("institution") or {}
    breakdown = institution.get("breakdown") or {}
    return MarketInvestorRecord(
        date=on,
        source_updated_at=_parse_offset_datetime(item.get("updatedAt")),
        individual_net_eok=net_eok(item.get("individual")),
        foreign_net_eok=net_eok(item.get("foreigner")),
        # 기관 합계는 breakdown이 아니라 institution 자신의 매수/매도로 계산한다.
        institution_net_eok=net_eok(institution),
        other_corp_net_eok=net_eok(item.get("otherCorporation")),
        breakdown=InstitutionBreakdown(
            **{field: net_eok(breakdown.get(key)) for field, key in _BREAKDOWN_FIELDS}
        ),
    )


_chart_limiter: RateLimiter | None = None


def _get_chart_limiter() -> RateLimiter:
    """지수 캔들(MARKET_INDICATOR_CHART 그룹, 초당 5건) 전용.

    1분봉 차트 한 번이 200봉 페이지를 여러 번 연달아 받는다 — 리미터 없이는 요청 하나가
    1초 안에 한도를 넘는다.
    """
    global _chart_limiter
    if _chart_limiter is None:
        permits = get_settings().toss.indicator_chart_permits_per_second
        _chart_limiter = RateLimiter(
            "toss-indicator-chart", permits_per_period=1, period_seconds=1.0 / permits, timeout_seconds=20.0
        )
    return _chart_limiter


def fetch_candles(symbol: str, interval: str, count: int, before: str | None = None) -> CandlesPage:
    """캔들(OHLCV). `interval` 1m/1d, `count` 최대 200. 최신순. 실패 시 빈 페이지."""
    try:
        return client.with_token_retry(lambda: _fetch_candles(symbol, interval, count, before))
    except Exception:
        log.error("Toss 캔들 조회 실패 symbol=%s interval=%s", symbol, interval, exc_info=True)
        return CandlesPage([], None)


def _fetch_candles(symbol: str, interval: str, count: int, before: str | None) -> CandlesPage:
    params: dict[str, str | int] = {"interval": interval, "count": count}
    if before is not None:
        params["before"] = before

    _get_chart_limiter().acquire()
    response = client.get_client().get(
        f"/api/v1/market-indicators/{symbol}/candles",
        params=params,
        headers=client.auth_headers(),
    )
    response.raise_for_status()
    result = (response.json().get("result") or {})
    candles = [c for c in (_to_candle(i) for i in (result.get("candles") or [])) if c is not None]
    return CandlesPage(candles=candles, next_before=result.get("nextBefore"))


def _to_candle(item: dict) -> TossCandle | None:
    ts = _parse_offset_datetime(item.get("timestamp"))
    if ts is None:
        return None
    prices = [item.get(k) for k in ("openPrice", "highPrice", "lowPrice", "closePrice")]
    if any(_to_float_or_none(p) is None for p in prices):
        return None  # OHLC 중 하나라도 없으면 캔들이 아니다
    o, h, low, c = (_to_float(p) for p in prices)
    return TossCandle(timestamp=ts, open=o, high=h, low=low, close=c, volume=_to_float(item.get("volume")))


def _to_float(value: str | None) -> float:
    parsed = _to_float_or_none(value)
    return parsed if parsed is not None else 0.0


def _to_float_or_none(value: str | None) -> float | None:
    try:
        return float((value or "").strip())
    except (ValueError, AttributeError):
        return None


def _parse_date(value: str | None) -> date | None:
    try:
        return date.fromisoformat((value or "").strip())
    except ValueError:
        return None


def _parse_offset_datetime(value: str | None) -> datetime | None:
    """"2026-06-11T18:10:00+09:00" → KST 벽시계(naive). 실패 시 None."""
    try:
        return datetime.fromisoformat((value or "").strip()).replace(tzinfo=None)
    except ValueError:
        return None
