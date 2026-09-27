"""지난 거래일 1분봉 보관소 — `(종목, 날짜)` → 그날 봉 전체.

키움 과거 분봉은 "기준일로 부른 900봉 페이지" 단위라, 날짜가 하루 넘어가면 어제 봉이 든 페이지를
**새 기준일로 다시** 받아야 했다. 어제 봉은 하루 내내 토스 저장소에 있었는데도 날짜가 바뀌면 버려졌다.
여기 날짜 단위로 넣어 두면 아침에 감시 풀 전체의 어제 봉을 키움에서 다시 받지 않는다.

**완성된 날만 넣는다.** 형성 중인 날을 넣으면 4일 동안 그대로 굳는다.
- 마감 확정(20:01) 뒤의 당일 저장소
- 키움 페이지에서 가장 이른 날을 뺀 나머지(가장 이른 날은 페이지 끝에서 잘려 있다)

지난 날 분봉을 들고 있는 곳은 여기 하나다(키움 페이지는 캐시하지 않는다).

**메모리 앞, DB(`stock_minute_candle`) 뒤.** 넣을 때 둘 다 쓰고, 꺼낼 때 메모리에 없으면 DB를 본다.
배포로 재시작해도 DB에 남아 다음 날 아침 어제 봉을 키움에서 다시 받지 않는다. DB가 죽어 있으면
메모리만으로 동작한다 — 그 기록 없이 돌 뿐 화면은 막지 않는다.

대가 — 수정주가(액면분할 등) 소급은 반영되지 않는다. 그래서 오래 두지 않는다(`purge`, 2주).
"""

import logging
import threading
import time
from datetime import date

from backend.leadingstock import infrastructure as store
from backend.leadingstock.domain import MinuteCandle
from backend.library.db import get_session_factory

log = logging.getLogger(__name__)

#: 메모리에 두는 기간. 지나면 DB에서 다시 올린다.
_TTL_SECONDS = 4 * 24 * 3600
#: 메모리 상한 — 감시 풀(~35종목) × 며칠 + 상세 화면으로 연 종목. 넘치면 오래 넣은 것부터 밀어낸다.
_MAX_ENTRIES = 300

_lock = threading.Lock()
_days: dict[tuple[str, date], tuple[float, list[MinuteCandle]]] = {}


def _key(stock_code: str) -> str:
    return stock_code.split("_")[0]


def get(stock_code: str, day: date) -> list[MinuteCandle] | None:
    """그날 봉 전체(시각 오름차순). 메모리에 없으면 DB에서 올린다. 어디에도 없으면 None."""
    key = _key(stock_code)
    remembered = _recall(key, day)
    if remembered is not None:
        return remembered
    try:
        with get_session_factory()() as session:
            bars = store.load_minute_day(session, key, day)
    except Exception:
        log.warning("지난 날 분봉 DB 조회 실패 stk_cd=%s day=%s", key, day, exc_info=True)
        return None
    if not bars:
        return None
    _remember(key, day, bars)
    return bars


def put(stock_code: str, day: date, bars: list[MinuteCandle]) -> None:
    """완성된 하루치를 넣는다. 비어 있으면 넣지 않는다 — 없는 날과 못 받은 날을 섞지 않는다."""
    if not bars:
        return
    key = _key(stock_code)
    ordered = sorted(bars, key=lambda c: c.date_time)
    _remember(key, day, ordered)
    try:
        with get_session_factory()() as session:
            store.save_minute_day(session, key, day, ordered)
    except Exception:
        log.warning("지난 날 분봉 DB 저장 실패 stk_cd=%s day=%s", key, day, exc_info=True)


def purge(before: date) -> int:
    """`before` 전의 날을 메모리와 DB에서 지운다. DB에서 지운 행 수를 돌려준다."""
    with _lock:
        for k in [k for k in _days if k[1] < before]:
            del _days[k]
    with get_session_factory()() as session:
        return store.purge_minute_days_before(session, before)


def _recall(key: str, day: date) -> list[MinuteCandle] | None:
    with _lock:
        entry = _days.get((key, day))
        if entry is None:
            return None
        stored_at, bars = entry
        if time.monotonic() - stored_at > _TTL_SECONDS:
            del _days[(key, day)]
            return None
        return bars


def _remember(key: str, day: date, ordered: list[MinuteCandle]) -> None:
    with _lock:
        _days[(key, day)] = (time.monotonic(), ordered)
        if len(_days) > _MAX_ENTRIES:
            oldest = sorted(_days, key=lambda k: _days[k][0])[: len(_days) - _MAX_ENTRIES]
            for k in oldest:
                del _days[k]


def put_page(stock_code: str, page: list[MinuteCandle]) -> None:
    """키움 페이지에서 완성된 날만 골라 넣는다. 가장 이른 날은 페이지 끝에서 잘려 있어 뺀다."""
    by_day: dict[date, list[MinuteCandle]] = {}
    for c in page:
        by_day.setdefault(c.date_time.date(), []).append(c)
    if not by_day:
        return
    cut = min(by_day)
    for day, bars in by_day.items():
        if day != cut:
            put(stock_code, day, bars)


def reset() -> None:
    """테스트 격리용."""
    with _lock:
        _days.clear()
