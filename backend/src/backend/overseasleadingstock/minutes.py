"""해외 종목 1분봉 저장소 — 최근 2거래일을 종목별로 들고 있다가 **새 봉만** 이어 받는다.

예전에는 차트가 60초마다 물을 때마다 2거래일치(KIS 120봉 × 12페이지 안팎)를 통째로 다시 받았다.
직전 거래일은 바뀌지 않고 최신 거래일도 뒤쪽 몇 봉만 늘어나는데 매번 전부를 받았고, 12번을
연달아 부르다 한도에 걸리면 한 페이지 때문에 차트가 통째로 비었다.

- 처음 열 때만 2거래일치를 받는다. 최신 거래일은 이어 붙일 칸, 직전 거래일은 끝난 날로 둔다.
- 그다음은 마지막 봉 조금 앞부터만 받는다 — 대개 한 페이지다. 진행 중이던 봉은 덮어쓴다.
- 현지 영업일(tymd)이 새로 보이면 날이 넘어간 것이다. 최신이던 날을 직전 거래일로 내린다.
- 미국 장이 닫혀 있는 동안 닫힌 뒤에 받은 값은 다시 받지 않는다(주말·휴장·장 밖).

"날"은 **미국 현지 영업일**로 가른다. 미국 장은 한국 자정을 넘나들어 한국 날짜로는 하루가 쪼개진다.
메모리에만 있다 — 해외 분봉을 쓰는 곳은 상세 차트뿐이라, 재시작 뒤 처음 열 때 한 번 다시 받으면 된다.
"""

import logging
import threading
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from backend.library.time import now
from backend.market import calendar
from backend.platform.kis import overseas_chart
from backend.platform.kis.overseas_chart import MinutePagesInterrupted, OverseasMinuteCandle

log = logging.getLogger(__name__)

#: 장중에 이보다 오래된 값이면 이어 받는다 — 화면이 60초마다 묻는다.
_FRESH = timedelta(seconds=60)
#: 마지막 봉부터 이만큼 거슬러 다시 받아 덮어쓴다 — 진행 중이던 봉이 그사이 확정됐을 수 있다.
_REWRITE = timedelta(minutes=2)
#: 이만큼 아무도 안 본 종목은 버린다.
_IDLE = timedelta(minutes=10)
_MAX_STOCKS = 50


@dataclass
class _Stock:
    latest_day: date
    latest: dict[datetime, OverseasMinuteCandle]
    previous: list[OverseasMinuteCandle]
    synced_at: datetime
    synced_while_closed: bool
    read_at: datetime = field(default_factory=now)

    def candles(self) -> list[OverseasMinuteCandle]:
        return self.previous + [self.latest[t] for t in sorted(self.latest)]


_lock = threading.Lock()
_stocks: dict[tuple[str, str], _Stock] = {}


def minute_candles(exchange: str, symbol: str) -> list[OverseasMinuteCandle]:
    """최근 2거래일 1분봉(시각 오름차순). 처음 열 때 받는 게 실패하면 예외를 올린다."""
    key = (exchange, symbol)
    at = now()
    closed = _us_closed()
    with _lock:
        _evict(at, keep=key)
        stock = _stocks.get(key)
        if stock is not None:
            stock.read_at = at
            if at - stock.synced_at <= _FRESH or (closed and stock.synced_while_closed):
                return stock.candles()

    if stock is None:
        return _load(key, at, closed)
    try:
        fresh = overseas_chart.fetch_minute_candles(exchange, symbol, since=max(stock.latest) - _REWRITE)
    except MinutePagesInterrupted as e:
        fresh, complete = e.candles, False   # 받은 새 봉은 붙이되, 다음에 다시 받게 둔다
    except Exception:
        log.warning("해외 분봉 이어 받기 실패 — 들고 있던 봉을 준다 (%s:%s)", exchange, symbol, exc_info=True)
        return stock.candles()
    else:
        complete = True
    with _lock:
        _extend(stock, fresh)
        if complete:
            stock.synced_at, stock.synced_while_closed = at, closed
        return stock.candles()


def _load(key: tuple[str, str], at: datetime, closed: bool) -> list[OverseasMinuteCandle]:
    """처음 여는 종목 — 2거래일치를 받는다. 중간에 끊기면 보여만 주고 들고 있지 않는다(다음에 다시)."""
    try:
        candles = overseas_chart.fetch_minute_candles(*key)
    except MinutePagesInterrupted as e:
        return sorted(e.candles, key=lambda c: c.date_time)
    if not candles:
        return []
    days = sorted({c.trading_day for c in candles}, reverse=True)
    latest_day = days[0]
    stock = _Stock(
        latest_day=latest_day,
        latest={c.date_time: c for c in candles if c.trading_day == latest_day},
        previous=sorted((c for c in candles if c.trading_day != latest_day), key=lambda c: c.date_time),
        synced_at=at,
        synced_while_closed=closed,
        read_at=at,
    )
    with _lock:
        _stocks[key] = stock
        return stock.candles()


def _extend(stock: _Stock, fresh: list[OverseasMinuteCandle]) -> None:
    """새 봉을 붙인다. 새 영업일이 보이면 최신이던 날을 직전 거래일로 내린다. 호출자가 락을 쥔다."""
    new_day = max((c.trading_day for c in fresh), default=stock.latest_day)
    if new_day > stock.latest_day:
        closing = {c.date_time: c for c in fresh if c.trading_day == stock.latest_day}  # 끝난 날의 마지막 봉들
        stock.previous = [x for _, x in sorted({**stock.latest, **closing}.items())]
        stock.latest, stock.latest_day = {}, new_day
    stock.latest.update((c.date_time, c) for c in fresh if c.trading_day == stock.latest_day)


def _evict(at: datetime, keep: tuple[str, str]) -> None:
    for key in [k for k, s in _stocks.items() if k != keep and at - s.read_at > _IDLE]:
        del _stocks[key]
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
