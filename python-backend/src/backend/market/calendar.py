"""휴장 판정 — 국내 개장일(KIS) / 지역별 휴장(토스) / 장 상태.

호출 빈도를 아끼려고 둘 다 하루 한 번만 외부를 두드리고 메모리에 캐시한다.
"""

import threading
from datetime import date, time, timedelta
from enum import Enum
from zoneinfo import ZoneInfo

from backend.library.time import KST, now, today
from backend.platform.kis import holiday as kis_holiday
from backend.platform.toss import market_calendar as toss_calendar

# --- 국내 개장일 (KIS) ---------------------------------------------------

_LOOKBACK_DAYS = 20  # 기준일을 20일 과거로 잡아 최근 10거래일 판정에 충분한 집합을 받는다

_holiday_lock = threading.Lock()
_cached_day: date | None = None
_open_days: frozenset[date] = frozenset()


def is_open(on: date) -> bool | None:
    """`on`이 주식시장 개장일인지.

    휴장일 목록을 못 받았으면(빈 집합) **판정 불가로 보고 None**을 준다 —
    False로 단정하면 멀쩡한 거래일 데이터가 화면에서 사라진다.
    """
    days = _refreshed_open_days()
    if not days:
        return None
    return on in days


def _refreshed_open_days() -> frozenset[date]:
    global _cached_day, _open_days
    current = today()
    if _cached_day == current and _open_days:
        return _open_days
    with _holiday_lock:
        if _cached_day == current and _open_days:
            return _open_days
        fetched = kis_holiday.fetch_open_days(current - timedelta(days=_LOOKBACK_DAYS))
        if fetched:
            _open_days = frozenset(fetched)
            _cached_day = current
        return _open_days


# --- 지역별 휴장 (토스) --------------------------------------------------


class Region(str, Enum):
    """쿼리 파라미터로 그대로 바인딩되도록 **값이 곧 코드**인 문자열 enum으로 둔다."""

    KR = "KR"
    US = "US"

    @property
    def code(self) -> str:
        return self.value

    @property
    def zone(self) -> ZoneInfo:
        return _ZONES[self]

    def today(self) -> date:
        """'오늘'은 지역의 **현지 날짜**다 — KR은 KST, US는 미 동부시각 기준."""
        return now().replace(tzinfo=KST).astimezone(self.zone).date()


# 지역별 현지 시간대. enum 값에 넣으면 스키마 직렬화가 깨져 밖에 둔다.
_ZONES = {Region.KR: ZoneInfo("Asia/Seoul"), Region.US: ZoneInfo("America/New_York")}


_calendar_lock = threading.Lock()
_calendar_cache: dict[Region, tuple[date, bool]] = {}


def is_holiday(region: Region) -> bool:
    """`region` 시장의 오늘이 휴장(주말·공휴일)인지. 조회 실패면 주말 폴백."""
    current = region.today()
    cached = _calendar_cache.get(region)
    if cached is not None and cached[0] == current:
        return cached[1]

    open_ = toss_calendar.is_trading_day(region.code, current)
    holiday = (not open_) if open_ is not None else current.weekday() >= 5
    with _calendar_lock:
        _calendar_cache[region] = (current, holiday)
    return holiday


# --- 장 상태 ------------------------------------------------------------

_TRADING_START = time(8, 0)   # NXT 프리마켓(08:00~08:50) 포함
_TRADING_END = time(20, 0)    # KRX 마감 후 NXT 애프터마켓(~20:00) 포함


def market_status() -> tuple[bool, bool]:
    """(휴장 여부, 거래시간 내 여부)."""
    current = now().time()
    return is_holiday(Region.KR), _TRADING_START <= current <= _TRADING_END


def reset() -> None:
    """테스트 격리용."""
    global _cached_day, _open_days
    _cached_day = None
    _open_days = frozenset()
    _calendar_cache.clear()
