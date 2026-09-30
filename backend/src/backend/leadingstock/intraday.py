"""당일 1분봉 저장소 — 종목별 오늘 분봉을 들고 있다가 **마지막 봉 이후만** 받아 뒤에 이어 붙인다.

TTL 캐시로는 안 된다. 만료되면 오늘치(최대 720봉)를 통째로 다시 받아야 하고, 만료와 다음 요청
사이에 캐시가 비어 그 틈에 온 요청이 브로커를 기다린다. 여기서는 한 번 채운 뒤 새 봉만 받으므로
갱신 한 번이 종목당 호출 한 번이다.

채우는 쪽은 둘이다.
- 감시 풀: 장중 20초마다 `sync`가 미리 채운다. 읽는 쪽은 대개 들고 있는 값을 바로 받는다.
- 그 밖의 종목(상세 화면으로 연 종목): 읽을 때 `ensure`가 낡았으면 그 자리에서 이어 받는다.

**얼마나 새것이어야 하는지는 읽는 쪽이 정한다**(`fresh_since`) — 장중엔 몇십 초, 마감 뒤엔
"마감이 굳은 뒤에 받았는가"다. 저장소는 종목마다 마지막으로 받은 벽시계 시각만 기록한다.

진행 중인 봉은 받을 때마다 값이 바뀐다 — 마지막 몇 봉은 다시 받아 **덮어쓴다**.
"""

import logging
import threading
from collections.abc import Iterable
from datetime import date, datetime, timedelta

from backend.leadingstock.domain import MinuteCandle
from backend.library.singleflight import SingleFlight
from backend.library.time import now, today
from backend.platform.toss import candles as toss_candles

log = logging.getLogger(__name__)

#: 마지막 봉부터 이만큼 거슬러 다시 받아 덮어쓴다 — 진행 중이던 봉이 그사이 확정됐을 수 있다.
_REWRITE = timedelta(minutes=2)
#: 종목 수 상한. 넘치면 가장 오래 안 읽힌 종목부터 버린다.
_MAX_STOCKS = 100

_lock = threading.Lock()
_day: date | None = None
_bars: dict[str, dict[datetime, MinuteCandle]] = {}
_synced_at: dict[str, datetime] = {}   # 마지막으로 받는 데 성공한 시각
_read_at: dict[str, datetime] = {}
#: 화면 요청끼리 같은 종목을 동시에 받으면 한 번만 부른다. 폴러·마감 확정은 여기 태우지 않는다 —
#: 마감 확정이 마감 전에 시작된 화면 요청에 얹히면 덜 찬 마지막 봉을 확정본으로 넘긴다.
_flight = SingleFlight()


def _key(stock_code: str) -> str:
    # 랭킹 코드는 `_AL`이 붙어 오고 상세 화면은 맨 코드로 온다 — 같은 종목으로 본다
    return stock_code.split("_")[0]


def sync(stock_codes: Iterable[str]) -> int:
    """감시 풀의 오늘 분봉을 이어 받는다. 실패한 종목 수를 돌려준다.

    한 종목이 실패해도 나머지는 계속한다. 실패한 종목은 기존 봉을 그대로 둔다.
    풀 밖의 종목도 상한 안에서는 유지한다 — 다시 열면 이어 받을 수 있다.
    """
    keys = [_key(c) for c in stock_codes]
    day = today()
    with _lock:
        _roll_over(day)
        _evict(keep=set(keys))

    failures = 0
    for key in keys:
        try:
            _sync_one(key, day)
        except Exception:
            failures += 1
            log.warning("당일 분봉 갱신 실패 stk_cd=%s", key, exc_info=True)
    return failures


def settle(stock_codes: Iterable[str]) -> tuple[dict[str, list[MinuteCandle]], int]:
    """마감 뒤 한 번 더 이어 받아 마지막 봉까지 확정한다. (확정된 종목별 오늘 봉, 실패 수)를 돌려준다.

    마지막 정규 갱신은 20:00 전이라 진행 중이던 마지막 봉이 덜 찬 채 남아 있다. 이번 회차에
    **성공한 종목만** 확정으로 본다 — 실패한 종목은 마지막 봉이 덜 찼을 수 있다.
    """
    started = now()
    keys = {_key(c) for c in stock_codes}
    failures = sync(keys)
    with _lock:
        if _day != today():
            return {}, failures
        settled = {
            key: _ordered(_bars[key])
            for key in keys
            if key in _bars and _synced_at.get(key, datetime.min) >= started
        }
    return settled, failures


def ensure(stock_code: str, fresh_since: datetime) -> list[MinuteCandle]:
    """오늘 분봉(시각 오름차순). `fresh_since` 이후에 받은 값이 없으면 그 자리에서 이어 받는다.

    받다가 실패하면 들고 있던 봉을 그대로 준다(없으면 빈 목록) — 낡은 봉이 없는 봉보다 낫다.
    """
    key = _key(stock_code)
    day = today()
    with _lock:
        _roll_over(day)
        _read_at[key] = now()
        if key in _bars and _synced_at.get(key, datetime.min) >= fresh_since:
            return _ordered(_bars[key])
        _evict(keep={key})
    try:
        _flight.do(key, lambda: _sync_if_stale(key, day, fresh_since))
    except Exception:
        log.error("당일 분봉 조회 실패 stk_cd=%s", key, exc_info=True)
    with _lock:
        return _ordered(_bars.get(key, {}))


def synced_since(stock_code: str, since: datetime) -> bool:
    """`since` 이후에 받는 데 성공했는가. 실패해 옛 봉을 돌려받았는지 가를 때 쓴다."""
    with _lock:
        return _synced_at.get(_key(stock_code), datetime.min) >= since


def _sync_if_stale(key: str, day: date, fresh_since: datetime) -> None:
    """선두가 막 받고 끝난 뒤에 들어온 요청이 또 받지 않게, 받기 직전에 한 번 더 본다."""
    with _lock:
        if _synced_at.get(key, datetime.min) >= fresh_since:
            return
    _sync_one(key, day)


def _sync_one(key: str, day: date) -> None:
    with _lock:
        have = _bars.get(key)
    since = max(have) - _REWRITE if have else None
    fresh = toss_candles.fetch_today_minute_candles(key, since)
    with _lock:
        if _day != day:  # 받는 사이 날짜가 넘어갔다 — 어제 봉을 오늘 칸에 넣지 않는다
            return
        merged = dict(_bars.get(key) or have or {})
        merged.update((c.date_time, c) for c in fresh)
        _bars[key] = merged
        _synced_at[key] = now()


def _ordered(bars: dict[datetime, MinuteCandle]) -> list[MinuteCandle]:
    return [bars[t] for t in sorted(bars)]


def _evict(keep: set[str]) -> None:
    """상한을 넘으면 오래 안 읽힌 종목부터 버린다. 호출자가 락을 쥔 상태여야 한다."""
    # 곧 들어올 종목(`keep`)까지 센다 — 받기 전에 자리를 비워 둬야 상한을 넘지 않는다
    overflow = len(set(_bars) | keep) - _MAX_STOCKS
    if overflow > 0:
        for key in sorted((k for k in _bars if k not in keep), key=lambda k: _read_at.get(k, datetime.min))[:overflow]:
            _drop(key)


def _drop(key: str) -> None:
    _bars.pop(key, None)
    _synced_at.pop(key, None)
    _read_at.pop(key, None)


def _roll_over(day: date) -> None:
    """날짜가 바뀌면 전부 버린다. 호출자가 락을 쥔 상태여야 한다."""
    global _day
    if _day != day:
        _bars.clear()
        _synced_at.clear()
        _read_at.clear()
        _day = day


def reset() -> None:
    """테스트 격리용."""
    global _day
    with _lock:
        _bars.clear()
        _synced_at.clear()
        _read_at.clear()
        _day = None
