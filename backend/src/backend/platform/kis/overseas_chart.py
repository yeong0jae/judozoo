"""KIS 해외주식 분봉·일봉.

두 API 모두 1회 응답 건수에 한도가 있어 커서를 옮겨가며 이어 받는다.

- 분봉(HHDFS76950200): 1회 120건. **다음조회 커서 `KEYB`는 현지시각(xymd+xhms) 기준**이며
  마지막 봉에서 1분을 뺀 값을 넣는다. 한국시각(kymd+khms)을 넣으면 엉뚱한 구간이 온다.
- 일봉(HHDFS76240000): 1회 100건. 커서 `BYMD`는 이전 페이지 마지막 일자 −1일.

캔들의 시각 자체는 **한국기준(kymd+khms)**을 쓴다. 커서용 현지시각과 혼동하지 않는다.

분봉은 캐시하지 않는다 — `overseasleadingstock.minutes`가 종목별로 들고 있다가 새 봉만 이어 받는다.
"""

import logging
from time import sleep
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

#: KIS 초당 거래건수 초과. HTTP 500에 실려 온다 — 잠깐 쉬었다 한 번 더 부르면 대개 지나간다.
_RATE_LIMITED = "EGW00201"

_MINUTE_FMT = "%Y%m%d%H%M%S"
_DAILY_FMT = "%Y%m%d"


@dataclass(frozen=True)
class OverseasMinuteCandle:
    date_time: datetime   # 한국시각
    trading_day: date     # 현지 영업일(tymd) — 미국 장은 한국 자정을 넘나들어 한국 날짜로는 하루가 쪼개진다
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


class MinutePagesInterrupted(RuntimeError):
    """뒷페이지에서 끊겼다. 그때까지 받은 봉은 `candles`에 있다 — 없는 것보다 낫지만 온전하지 않다."""

    def __init__(self, message: str, candles: list["OverseasMinuteCandle"]) -> None:
        super().__init__(message)
        self.candles = candles


def fetch_minute_candles(excd: str, symb: str, since: datetime | None = None) -> list[OverseasMinuteCandle]:
    """최신부터 거꾸로 받은 1분봉.

    - `since`가 없으면 최근 [SESSION_DAYS]거래일. 현지영업일(tymd)이 그보다 많이 보이면 멈추고
      최신 거래일만 남긴다.
    - `since`(한국시각)가 있으면 그 시각 이후 봉만 — 그보다 이른 봉이 보이면 멈춘다. 이어 받기용이라
      대개 첫 페이지에서 끝난다.

    첫 페이지가 실패하면 예외를 올린다. 뒷페이지가 실패하면 받은 데까지를 `MinutePagesInterrupted`에 담아 올린다.
    """
    collected: list[dict[str, Any]] = []
    trading_days: list[str] = []  # 등장 순서를 보존하되 중복은 제외
    keyb = ""
    next_ = ""

    for page in range(MAX_MINUTE_PAGES):
        try:
            items = _fetch_minute_page(excd, symb, next_, keyb)
        except Exception as e:
            if page == 0:
                raise
            log.warning("KIS 해외 분봉 뒷페이지 실패 — 받은 데까지만 (%s:%s, %d페이지)", excd, symb, page + 1)
            raise MinutePagesInterrupted(str(e), _finish(collected, trading_days, since)) from e
        if not items:
            break
        collected += items
        for item in items:
            tymd = str(item.get("tymd", "")).strip()
            if tymd and tymd not in trading_days:
                trading_days.append(tymd)
        if since is None and len(trading_days) > SESSION_DAYS:
            break  # 다음 거래일까지 받았으니 충분
        if since is not None and any(
            (c := _to_minute_candle(i)) is not None and c.date_time < since for i in items
        ):
            break  # 이미 가진 구간에 닿았다

        last = items[-1]  # 페이지는 최신→과거라 마지막이 가장 이른 봉
        cursor = _parse_local_cursor(last)
        if cursor is None:
            break
        keyb = (cursor - timedelta(minutes=1)).strftime(_MINUTE_FMT)
        next_ = "1"

    return _finish(collected, trading_days, since)


def _finish(collected: list[dict[str, Any]], trading_days: list[str], since: datetime | None) -> list[OverseasMinuteCandle]:
    candles = [c for c in (_to_minute_candle(item) for item in collected) if c is not None]
    if since is not None:
        return [c for c in candles if c.date_time >= since]
    keep_days = set(sorted(trading_days, reverse=True)[:SESSION_DAYS])
    return [c for c in candles if c.trading_day.strftime(_DAILY_FMT) in keep_days]


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
    """실패 시 예외를 올린다 — 페이징 도중 조용히 잘리면 봉이 비는 걸 눈치채기 어렵다.

    **오류 본문을 메시지에 싣는다.** KIS는 한도 초과도 HTTP 500으로 준다 — 상태 코드만으로는
    무엇이 문제인지 알 수 없다. 한도 초과(`EGW00201`)면 1초 쉬고 한 번만 다시 부른다.
    """
    for attempt in (1, 2):
        response = _get_minute_page(excd, symb, next_, keyb)
        body = _json_or_empty(response)
        if response.status_code == 200 and body.get("rt_cd") == "0":
            return body.get("output2") or []
        msg_cd, msg1 = body.get("msg_cd"), body.get("msg1")
        if msg_cd == _RATE_LIMITED and attempt == 1:
            log.warning("KIS 해외 분봉 한도 초과 — 1초 뒤 다시 (%s:%s)", excd, symb)
            sleep(1.0)
            continue
        raise RuntimeError(
            f"KIS 해외 분봉 오류: HTTP {response.status_code} {msg_cd} {msg1} ({excd}:{symb}, KEYB={keyb or '-'})"
        )
    return []  # 닿지 않는다 — 두 번째 시도는 반환하거나 올린다


def _json_or_empty(response) -> dict[str, Any]:
    try:
        body = response.json()
    except ValueError:
        return {}
    return body if isinstance(body, dict) else {}


def _get_minute_page(excd: str, symb: str, next_: str, keyb: str):
    return get_client().get(
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
    day = _parse_date(item.get("tymd"))
    if at is None or day is None:
        return None
    return OverseasMinuteCandle(
        date_time=at,
        trading_day=day,
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
