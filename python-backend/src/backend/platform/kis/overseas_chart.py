"""KIS 해외주식 분봉·일봉.

두 API 모두 1회 응답 건수에 한도가 있어 커서를 옮겨가며 이어 받는다.

- 분봉(HHDFS76950200): 1회 120건. **다음조회 커서 `KEYB`는 현지시각(xymd+xhms) 기준**이며
  마지막 봉에서 1분을 뺀 값을 넣는다. 한국시각(kymd+khms)을 넣으면 엉뚱한 구간이 온다.
- 일봉(HHDFS76240000): 1회 100건. 커서 `BYMD`는 이전 페이지 마지막 일자 −1일.

캔들의 시각 자체는 **한국기준(kymd+khms)**을 쓴다. 커서용 현지시각과 혼동하지 않는다.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any

from backend.library.cache import ttl_cache
from backend.platform.kis.client import auth_headers, get_client

log = logging.getLogger(__name__)

SESSION_DAYS = 2       # 분봉 표시 거래일 수 (최신일 프리~애프터 + 직전일 정규장)
MAX_MINUTE_PAGES = 20  # 2거래일(~1,350분/120) ≈ 12페이지에 여유
DAILY_COUNT = 200      # 일봉 표시 거래일 수 (국내와 동일)
MAX_DAILY_PAGES = 3    # 1회 100건 한도라 200건 = 2페이지에 여유

_MINUTE_FMT = "%Y%m%d%H%M%S"
_DAILY_FMT = "%Y%m%d"


@dataclass(frozen=True)
class OverseasMinuteCandle:
    date_time: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int
    trading_value: float


@dataclass(frozen=True)
class OverseasDailyCandle:
    date: date
    open: float
    high: float
    low: float
    close: float
    volume: int


@ttl_cache("kisOverseasMinuteCandles", ttl_seconds=60, maxsize=60)
def fetch_minute_candles(excd: str, symb: str) -> list[OverseasMinuteCandle]:
    """최근 [SESSION_DAYS]거래일 1분봉.

    현지영업일(tymd)이 SESSION_DAYS를 넘기 시작하면(다음 거래일 데이터가 보이면) 중단한 뒤
    최신 거래일만 남긴다.
    """
    collected: list[dict[str, Any]] = []
    trading_days: list[str] = []  # 등장 순서를 보존하되 중복은 제외
    keyb = ""
    next_ = ""

    for _ in range(MAX_MINUTE_PAGES):
        items = _fetch_minute_page(excd, symb, next_, keyb)
        if not items:
            break
        collected += items
        for item in items:
            tymd = str(item.get("tymd", "")).strip()
            if tymd and tymd not in trading_days:
                trading_days.append(tymd)
        if len(trading_days) > SESSION_DAYS:
            break  # 다음 거래일까지 받았으니 충분

        last = items[-1]  # 페이지는 최신→과거라 마지막이 가장 이른 봉
        cursor = _parse_local_cursor(last)
        if cursor is None:
            break
        keyb = (cursor - timedelta(minutes=1)).strftime(_MINUTE_FMT)
        next_ = "1"

    keep_days = set(sorted(trading_days, reverse=True)[:SESSION_DAYS])
    candles = (
        _to_minute_candle(item)
        for item in collected
        if str(item.get("tymd", "")).strip() in keep_days
    )
    return [c for c in candles if c is not None]


@ttl_cache("kisOverseasDailyCandles", ttl_seconds=30, maxsize=60)
def fetch_daily_candles(excd: str, symb: str) -> list[OverseasDailyCandle]:
    """최근 [DAILY_COUNT]거래일 일봉. 최신→과거 순."""
    collected: list[dict[str, Any]] = []
    bymd = ""

    for _ in range(MAX_DAILY_PAGES):
        items = _fetch_daily_page(excd, symb, bymd)
        if not items:
            break
        collected += items
        if len(collected) >= DAILY_COUNT or len(items) < 100:
            break  # 목표를 채웠거나 더 없음

        oldest = _parse_date(items[-1].get("xymd"))
        if oldest is None:
            break
        bymd = (oldest - timedelta(days=1)).strftime(_DAILY_FMT)

    candles = (_to_daily_candle(item) for item in collected[:DAILY_COUNT])
    return [c for c in candles if c is not None]


def _fetch_minute_page(excd: str, symb: str, next_: str, keyb: str) -> list[dict[str, Any]]:
    """실패 시 예외를 올린다 — 페이징 도중 조용히 잘리면 봉이 비는 걸 눈치채기 어렵다."""
    response = get_client().get(
        "/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice",
        params={
            "AUTH": "",
            "EXCD": excd,
            "SYMB": symb,
            "NMIN": "1",
            "PINC": "1",  # 전일포함 — 장 초반에도 직전 세션 봉으로 채움
            "NEXT": next_,
            "NREC": "120",
            "FILL": "",
            "KEYB": keyb,
        },
        headers=auth_headers("HHDFS76950200"),
    )
    response.raise_for_status()
    body = response.json()
    if body.get("rt_cd") != "0":
        raise RuntimeError(f"KIS 해외 분봉 오류: {body.get('msg1')} ({excd}:{symb})")
    return body.get("output2") or []


def _fetch_daily_page(excd: str, symb: str, bymd: str) -> list[dict[str, Any]]:
    """실패 시 빈 페이지 — 일봉은 없으면 차트만 짧아지고 끝이라 호출측을 깨우지 않는다."""
    try:
        response = get_client().get(
            "/uapi/overseas-price/v1/quotations/dailyprice",
            params={
                "AUTH": "",
                "EXCD": excd,
                "SYMB": symb,
                "GUBN": "0",   # 0:일
                "BYMD": bymd,  # 공란=오늘, 값 있으면 그 일자 이하
                "MODP": "1",   # 수정주가 반영
                "KEYB": "",
            },
            headers=auth_headers("HHDFS76240000"),
        )
        response.raise_for_status()
        body = response.json()
    except Exception:
        log.exception("KIS 해외 일봉 조회 실패 (%s:%s, bymd=%s)", excd, symb, bymd)
        return []

    if body.get("rt_cd") != "0":
        log.error("KIS 해외 일봉 오류: %s (%s:%s, bymd=%s)", body.get("msg1"), excd, symb, bymd)
        return []
    return body.get("output2") or []


def _parse_local_cursor(item: dict[str, Any]) -> datetime | None:
    """다음 페이지 커서는 **현지시각**(xymd+xhms)이다. 한국시각을 쓰면 구간이 어긋난다."""
    return _parse_datetime(item.get("xymd"), item.get("xhms"))


def _to_minute_candle(item: dict[str, Any]) -> OverseasMinuteCandle | None:
    # 봉의 시각은 한국기준(kymd+khms). 커서용 현지시각과 다르다.
    at = _parse_datetime(item.get("kymd"), item.get("khms"))
    if at is None:
        return None
    return OverseasMinuteCandle(
        date_time=at,
        open=_to_float(item.get("open")),
        high=_to_float(item.get("high")),
        low=_to_float(item.get("low")),
        close=_to_float(item.get("last")),
        volume=_to_int(item.get("evol")),
        trading_value=_to_float(item.get("eamt")),
    )


def _to_daily_candle(item: dict[str, Any]) -> OverseasDailyCandle | None:
    at = _parse_date(item.get("xymd"))
    if at is None:
        return None
    return OverseasDailyCandle(
        date=at,
        open=_to_float(item.get("open")),
        high=_to_float(item.get("high")),
        low=_to_float(item.get("low")),
        close=_to_float(item.get("clos")),
        volume=_to_int(item.get("tvol")),
    )


def _parse_datetime(ymd: Any, hms: Any) -> datetime | None:
    try:
        return datetime.strptime(f"{str(ymd).strip()}{str(hms).strip().zfill(6)}", _MINUTE_FMT)
    except (TypeError, ValueError):
        return None


def _parse_date(ymd: Any) -> date | None:
    try:
        return datetime.strptime(str(ymd).strip(), _DAILY_FMT).date()
    except (TypeError, ValueError):
        return None


def _to_float(value: Any) -> float:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return 0.0


def _to_int(value: Any) -> int:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0
