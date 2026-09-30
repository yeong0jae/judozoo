"""해외 종목 지난 거래일 1분봉 보관소 — `(거래소, 종목, 현지 영업일)` → 그날 봉 전체.

처음 여는 종목은 KIS에서 2거래일치(12페이지 안팎)를 받아야 했다. 직전 거래일은 이미 끝난 날인데도
재시작하거나 메모리에서 정리되면 매번 다시 받았고, 몰리면 KIS 한도(EGW00201)에 걸렸다.
여기 넣어 두면 처음 열 때 직전 거래일은 여기서 꺼내고 그 뒤 봉만 받는다.

**완성된 날만 넣는다.** 형성 중인 날을 넣으면 그대로 굳는다(`minutes`가 넣는 때를 정한다).
국내 `leadingstock.minute_archive`와 같은 구조다 — 메모리 앞, DB(`overseas_minute_candle`) 뒤.
DB가 죽어 있으면 메모리만으로 동작한다. 2주 지나면 지운다(`purge`).
"""

import logging
import threading
import time
from datetime import date

from backend.library.db import get_session_factory
from backend.overseasleadingstock import infrastructure as store
from backend.platform.kis.overseas_chart import OverseasMinuteCandle

log = logging.getLogger(__name__)

#: 메모리에 두는 기간. 지나면 DB에서 다시 올린다.
_TTL_SECONDS = 4 * 24 * 3600
#: 메모리 상한 — 상세 화면으로 연 종목 × 며칠.
_MAX_ENTRIES = 200

_lock = threading.Lock()
_days: dict[tuple[str, str, date], tuple[float, list[OverseasMinuteCandle]]] = {}


def recent(exchange: str, symbol: str, limit: int) -> list[tuple[date, list[OverseasMinuteCandle]]]:
    """최근 완성본을 메모리 우선으로 읽는다. DB 장애 중에도 메모리의 지난 봉을 유지한다."""
    with _lock:
        dates = [d for e, s, d in _days if (e, s) == (exchange, symbol)]
    remembered = {}
    for day in dates:
        bars = _recall(exchange, symbol, day)
        if bars:
            remembered[day] = bars
    if len(remembered) < limit:
        try:
            with get_session_factory()() as session:
                for day in store.latest_days(session, exchange, symbol, limit):
                    if day not in remembered:
                        bars = store.load_day(session, exchange, symbol, day)
                        if bars:
                            _remember(exchange, symbol, day, bars)
                            remembered[day] = bars
        except Exception:
            log.warning("해외 지난 날 분봉 DB 조회 실패 (%s:%s)", exchange, symbol, exc_info=True)
    return [(day, remembered[day]) for day in sorted(remembered, reverse=True)[:limit]]


def put(exchange: str, symbol: str, day: date, bars: list[OverseasMinuteCandle]) -> None:
    """완성된 하루치를 넣는다. 비어 있으면 넣지 않는다 — 없는 날과 못 받은 날을 섞지 않는다."""
    if not bars:
        return
    ordered = sorted(bars, key=lambda c: c.date_time)
    _remember(exchange, symbol, day, ordered)
    try:
        with get_session_factory()() as session:
            store.save_day(session, exchange, symbol, day, ordered)
    except Exception:
        log.warning("해외 지난 날 분봉 DB 저장 실패 (%s:%s, %s)", exchange, symbol, day, exc_info=True)


def purge(before: date) -> int:
    """`before` 전의 날을 메모리와 DB에서 지운다. DB에서 지운 행 수를 돌려준다."""
    with _lock:
        for k in [k for k in _days if k[2] < before]:
            del _days[k]
    with get_session_factory()() as session:
        return store.purge_before(session, before)


def _recall(exchange: str, symbol: str, day: date) -> list[OverseasMinuteCandle] | None:
    with _lock:
        entry = _days.get((exchange, symbol, day))
        if entry is None:
            return None
        stored_at, bars = entry
        if time.monotonic() - stored_at > _TTL_SECONDS:
            del _days[(exchange, symbol, day)]
            return None
        return bars


def _remember(exchange: str, symbol: str, day: date, ordered: list[OverseasMinuteCandle]) -> None:
    with _lock:
        _days[(exchange, symbol, day)] = (time.monotonic(), ordered)
        if len(_days) > _MAX_ENTRIES:
            oldest = sorted(_days, key=lambda k: _days[k][0])[: len(_days) - _MAX_ENTRIES]
            for k in oldest:
                del _days[k]


def reset() -> None:
    """테스트 격리용."""
    with _lock:
        _days.clear()
