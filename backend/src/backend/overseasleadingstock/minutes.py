"""해외 당일 분봉 저장소와 지난 거래일 보관소를 합쳐 최근 2거래일 차트를 만든다.

종목별 저장소에는 갱신 중인 한 거래일만 둔다. 지난 날은 minute_archive(메모리 + DB)가
소유한다. 미국 현지 영업일(tymd)의 새 봉을 완전히 받으면 이전 날을 보관한 뒤 전환한다.
한국 자정에는 초기화하지 않는다. 장중 30초 갱신과 마감 확정 작업은 유지한다.
"""

import logging
import threading
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from backend.library.singleflight import SingleFlight
from backend.library.time import KST, now
from backend.market import calendar
from backend.overseasleadingstock import minute_archive
from backend.platform.kis import overseas_chart
from backend.platform.kis.overseas_chart import MinutePagesInterrupted, OverseasMinuteCandle

log = logging.getLogger(__name__)

#: 장중에 이보다 오래된 값이면 이어 받는다 — 화면이 30초마다 묻는다(국내 1분 차트와 같다).
_FRESH = timedelta(seconds=30)
#: 마지막 봉부터 이만큼 거슬러 다시 받아 덮어쓴다 — 진행 중이던 봉이 그사이 확정됐을 수 있다.
_REWRITE = timedelta(minutes=2)
_MAX_STOCKS = 50
#: 보관소의 최신 날이 이보다 오래됐으면 쓰지 않는다 — 그 뒤를 이어 받으면 2거래일치보다 더 받게 된다.
_ARCHIVE_REACH = timedelta(days=4)

Day = tuple[date, list[OverseasMinuteCandle]]


@dataclass
class _Stock:
    latest_day: date
    latest: dict[datetime, OverseasMinuteCandle]
    synced_at: datetime
    synced_while_closed: bool
    read_at: datetime = field(default_factory=now)

    def latest_bars(self) -> list[OverseasMinuteCandle]:
        return [self.latest[t] for t in sorted(self.latest)]


_lock = threading.Lock()
_stocks: dict[tuple[str, str], _Stock] = {}
#: 같은 종목을 동시에 받으면 한 번만 부른다 — 처음 열기는 KIS 12페이지 안팎이라, 인기 종목을
#: 여럿이 동시에 열면 곧장 한도에 걸렸다. 마감 확정(`settle`)은 태우지 않는다(국내와 같은 이유).
_flight = SingleFlight()


def minute_candles(exchange: str, symbol: str) -> list[OverseasMinuteCandle]:
    """최근 2거래일 1분봉(시각 오름차순). 처음 열 때 받는 게 실패하면 예외를 올린다."""
    key = (exchange, symbol)
    at = now()
    closed = _us_closed()
    with _lock:
        _evict(keep=key)
        stock = _stocks.get(key)
        if stock is not None:
            stock.read_at = at
            fresh = _is_fresh(stock, at, closed)
        else:
            fresh = False
    if fresh:
        return _chart(key, stock)
    return _flight.do(key, lambda: _refresh(key, at, closed))


def _chart(key: tuple[str, str], stock: _Stock) -> list[OverseasMinuteCandle]:
    """지난 봉은 보관소에서 읽고 당일 봉과 응답에서만 합친다."""
    with _lock:
        day, current = stock.latest_day, stock.latest_bars()
    archived = minute_archive.recent(*key, overseas_chart.SESSION_DAYS)
    previous = next((bars for d, bars in archived if d < day), [])
    return sorted(previous + current, key=lambda c: c.date_time)


def _local_day(at: datetime) -> date:
    return at.replace(tzinfo=KST).astimezone(calendar.Region.US.zone).date()


def _is_fresh(stock: _Stock, at: datetime, closed: bool) -> bool:
    return at - stock.synced_at <= _FRESH or (
        closed and stock.synced_while_closed and _local_day(at) == _local_day(stock.synced_at)
    )


def _refresh(key: tuple[str, str], at: datetime, closed: bool) -> list[OverseasMinuteCandle]:
    """처음 열거나 새 봉을 이어 받는다. 선두가 막 받고 끝난 뒤에 들어온 요청은 받지 않고 그 값을 쓴다."""
    exchange, symbol = key
    with _lock:
        stock = _stocks.get(key)
        fresh_enough = stock is not None and _is_fresh(stock, at, closed)
    if fresh_enough:
        return _chart(key, stock)
    if stock is None:
        return _load(key, at, closed)
    if _local_day(at) - stock.latest_day > _ARCHIVE_REACH:
        # 오래 보관한 종목을 며칠 뒤 다시 열 때 수십 일치를 이어 받지 않는다.
        try:
            return _load(key, at, closed) or _chart(key, stock)
        except Exception:
            log.warning("해외 분봉 재조회 실패 — 기존 봉 유지 (%s:%s)", *key, exc_info=True)
            return _chart(key, stock)
    try:
        fresh = overseas_chart.fetch_minute_candles(exchange, symbol, since=max(stock.latest) - _REWRITE)
    except MinutePagesInterrupted as e:
        fresh, complete = e.candles, False   # 받은 새 봉은 붙이되, 다음에 다시 받게 둔다
    except Exception:
        log.warning("해외 분봉 이어 받기 실패 — 들고 있던 봉을 준다 (%s:%s)", exchange, symbol, exc_info=True)
        return _chart(key, stock)
    else:
        complete = True
    if not complete:
        # 새 거래일로 전환하다 끊겼으면 전날 확정·저장을 하지 않고 다음 요청에서 재시도한다.
        merged = {c.date_time: c for c in _chart(key, stock)}
        merged.update((c.date_time, c) for c in fresh)
        days = set(sorted({c.trading_day for c in merged.values()}, reverse=True)[:2])
        return [c for _, c in sorted(merged.items()) if c.trading_day in days]
    _extend(key, stock, fresh)
    with _lock:
        stock.synced_at, stock.synced_while_closed = at, closed
    return _chart(key, stock)


def settle() -> int:
    """마감 확정 — 들고 있는 종목의 최신 거래일을 한 번 더 받아 굳힌 뒤 보관소에 넘긴다.

    장이 끝나고 부르므로 이때 받은 최신 거래일은 끝난 날이다. 실패한 종목 수를 돌려준다.
    **들고 있는 종목만** 넘긴다 — 상한으로 정리한 종목은 다음에 열 때 보관소에서 복원한다.
    """
    with _lock:
        keys = list(_stocks)
    failures = 0
    for key in keys:
        with _lock:
            stock = _stocks.get(key)
        if stock is None:
            continue
        try:
            fresh = overseas_chart.fetch_minute_candles(*key, since=max(stock.latest) - _REWRITE)
        except Exception:
            failures += 1
            log.warning("해외 분봉 마감 확정 실패 (%s:%s)", *key, exc_info=True)
            continue
        at = now()
        _extend(key, stock, fresh)
        with _lock:
            stock.synced_at, stock.synced_while_closed = at, True
            latest = (stock.latest_day, stock.latest_bars())
        minute_archive.put(*key, *latest)
    return failures


def _load(key: tuple[str, str], at: datetime, closed: bool) -> list[OverseasMinuteCandle]:
    """처음 여는 종목. 보관소에 끝난 날이 있으면 그 뒤 봉만 받고, 없으면 2거래일치를 받는다.

    중간에 끊기면 보여만 주고 들고 있지 않는다(다음에 다시).
    """
    archived = minute_archive.recent(*key, overseas_chart.SESSION_DAYS)
    if archived and _local_day(at) - archived[0][0] <= _ARCHIVE_REACH:
        last = archived[0][1][-1].date_time
        try:
            fresh = overseas_chart.fetch_minute_candles(*key, since=last + timedelta(minutes=1))
        except MinutePagesInterrupted as e:
            return sorted(archived[0][1] + e.candles, key=lambda c: c.date_time)
        split = _after_archive(archived, fresh)
        if split is not None:
            latest, finished = split
            for day in finished:
                minute_archive.put(*key, *day)
            return _hold(key, latest, at, closed)

    try:
        candles = overseas_chart.fetch_minute_candles(*key)
    except MinutePagesInterrupted as e:
        return sorted(e.candles, key=lambda c: c.date_time)
    if not candles:
        return []
    by_day = _by_day(candles)
    days = sorted(by_day, reverse=True)
    latest = (days[0], by_day[days[0]])
    previous = (days[1], by_day[days[1]]) if len(days) > 1 else None
    # 받는 쪽이 세 번째 날이 보일 때까지 받으므로 직전 거래일은 온전하다
    if previous:
        minute_archive.put(*key, *previous)
    return _hold(key, latest, at, closed)


def _after_archive(archived: list[Day], fresh: list[OverseasMinuteCandle]) -> tuple[Day, list[Day]] | None:
    """보관소의 날 뒤에 새로 받은 봉을 이어 최신·직전 거래일로 가른다. 두 날이 안 되면 None(처음처럼 받는다).

    새로 받은 날 중 최신이 아닌 날은 끝난 날이다 — 함께 돌려줘 보관소에 넘기게 한다.
    """
    by_day = dict(archived) | _by_day(fresh)
    days = sorted(by_day, reverse=True)
    if len(days) < 2:
        return None
    kept = {d for d, _ in archived}
    finished = [(d, by_day[d]) for d in days[1:] if d not in kept]
    return (days[0], by_day[days[0]]), finished


def _by_day(candles: list[OverseasMinuteCandle]) -> dict[date, list[OverseasMinuteCandle]]:
    out: dict[date, list[OverseasMinuteCandle]] = {}
    for c in sorted(candles, key=lambda c: c.date_time):
        out.setdefault(c.trading_day, []).append(c)
    return out


def _hold(key: tuple[str, str], latest: Day, at: datetime, closed: bool) -> list[OverseasMinuteCandle]:
    stock = _Stock(
        latest_day=latest[0],
        latest={c.date_time: c for c in latest[1]},
        synced_at=at,
        synced_while_closed=closed,
        read_at=at,
    )
    with _lock:
        _stocks[key] = stock
    return _chart(key, stock)


def _extend(key: tuple[str, str], stock: _Stock, fresh: list[OverseasMinuteCandle]) -> None:
    """완성된 이전 날을 보관소에 넘긴 뒤 당일 칸을 새 미국 거래일로 전환한다."""
    with _lock:
        merged = dict(stock.latest)
        merged.update((c.date_time, c) for c in fresh if c.trading_day >= stock.latest_day)
    by_day = _by_day(list(merged.values()))
    new_day = max(by_day)
    for day in sorted(by_day):
        if day < new_day:
            minute_archive.put(*key, day, by_day[day])
    with _lock:
        stock.latest_day = new_day
        stock.latest = {c.date_time: c for c in by_day[new_day]}


def _evict(keep: tuple[str, str]) -> None:
    overflow = len(_stocks) + (keep not in _stocks) - _MAX_STOCKS
    if overflow > 0:
        for key in sorted((k for k in _stocks if k != keep), key=lambda k: _stocks[k].read_at)[:overflow]:
            del _stocks[key]


def _us_closed() -> bool:
    try:
        holiday, trading_hours = calendar.us_market_status()
    except Exception:
        return False  # 모르면 열려 있다고 본다 — 받는 쪽이 안전하다
    return holiday or not trading_hours


def reset() -> None:
    """테스트 격리용."""
    with _lock:
        _stocks.clear()
