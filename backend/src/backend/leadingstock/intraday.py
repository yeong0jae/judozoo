"""당일 1분봉 저장소 — 후보 종목의 오늘 분봉을 들고 있다가 장중 20초마다 뒤에 이어 붙인다.

TTL 캐시로는 안 된다. 만료되면 오늘치(최대 720봉)를 통째로 다시 받아야 하고, 만료와 다음 요청
사이에 캐시가 비어 그 틈에 온 요청이 브로커를 기다린다. 여기서는 한 번 채운 뒤 **마지막 봉 이후만**
받아 붙이므로 갱신 한 번이 종목당 호출 한 번이고, 읽는 쪽은 늘 들고 있는 값을 받는다.

진행 중인 봉은 받을 때마다 값이 바뀐다 — 마지막 몇 봉은 다시 받아 **덮어쓴다**.
갱신을 놓치면 믿지 않는다(`get`이 None). 읽는 쪽은 그때 직접 받아 온다.
"""

import logging
import threading
import time
from collections.abc import Iterable
from datetime import date, datetime, timedelta

from backend.leadingstock.domain import MinuteCandle
from backend.library.time import today
from backend.platform.toss import candles as toss_candles

log = logging.getLogger(__name__)

#: 마지막 봉부터 이만큼 거슬러 다시 받아 덮어쓴다 — 진행 중이던 봉이 그사이 확정됐을 수 있다.
_REWRITE = timedelta(minutes=2)
#: 마지막 갱신이 이보다 오래되면 저장된 값을 내주지 않는다. 20초 주기에서 두 번 놓친 셈이다.
STALE_SECONDS = 50

_lock = threading.Lock()
_day: date | None = None
_bars: dict[str, dict[datetime, MinuteCandle]] = {}
_synced_at: dict[str, float] = {}


def _key(stock_code: str) -> str:
    # 랭킹 코드는 `_AL`이 붙어 오고 상세 화면은 맨 코드로 온다 — 같은 종목으로 본다
    return stock_code.split("_")[0]


def sync(stock_codes: Iterable[str]) -> int:
    """이 종목들의 오늘 분봉을 이어 받는다. 목록에서 빠진 종목은 버린다. 실패한 종목 수를 돌려준다.

    한 종목이 실패해도 나머지는 계속한다. 실패한 종목은 기존 봉을 그대로 둔다.
    """
    keys = [_key(c) for c in stock_codes]
    day = today()
    with _lock:
        _roll_over(day)
        for gone in set(_bars) - set(keys):
            _bars.pop(gone, None)
            _synced_at.pop(gone, None)

    failures = 0
    for key in keys:
        try:
            _sync_one(key, day)
        except Exception:
            failures += 1
            log.warning("당일 분봉 갱신 실패 stk_cd=%s", key, exc_info=True)
    return failures


def _sync_one(key: str, day: date) -> None:
    with _lock:
        have = _bars.get(key)
    since = max(have) - _REWRITE if have else None
    fresh = toss_candles.fetch_today_minute_candles(key, since)
    with _lock:
        if _day != day:  # 받는 사이 날짜가 넘어갔다 — 어제 봉을 오늘 칸에 넣지 않는다
            return
        merged = dict(have or {})
        merged.update((c.date_time, c) for c in fresh)
        _bars[key] = merged
        _synced_at[key] = time.monotonic()


def get(stock_code: str) -> list[MinuteCandle] | None:
    """들고 있는 오늘 분봉(시각 오름차순). 없거나 갱신이 끊겼으면 None."""
    key = _key(stock_code)
    with _lock:
        if _day != today():
            return None
        synced = _synced_at.get(key)
        if synced is None or time.monotonic() - synced > STALE_SECONDS:
            return None
        bars = _bars[key]
        return [bars[t] for t in sorted(bars)]


def _roll_over(day: date) -> None:
    """날짜가 바뀌면 전부 버린다. 호출자가 락을 쥔 상태여야 한다."""
    global _day
    if _day != day:
        _bars.clear()
        _synced_at.clear()
        _day = day


def reset() -> None:
    """테스트 격리용."""
    global _day
    with _lock:
        _bars.clear()
        _synced_at.clear()
        _day = None
