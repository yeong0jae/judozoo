"""시황 폴러 — 목록 시세(30초) / 선물 투자자(60초).

"당일 누적"을 스냅샷으로 찍어두는 게 목적이다. 세션별 순매수는 이 스냅샷들의
경계 diff로 계산되므로, 폴러가 돌지 않은 과거는 **소급이 불가능**하다.
"""

import logging
from datetime import datetime, time, timedelta

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.interval import IntervalTrigger

from backend.library import metrics, tracing
from backend.library.db import get_session_factory
from backend.library.time import KST, now, today
from backend.market import application, calendar
from backend.platform.kis import futures as kis_futures
from backend.stock.domain import Market

log = logging.getLogger(__name__)

_FUTURES_START = time(8, 45)   # 선물 개장
_FUTURES_END = time(15, 45)    # 선물 마감


_JOB = "futures-investor-poller"


@tracing.traced_job(_JOB)
def poll_futures_investors() -> None:
    if calendar.is_holiday(calendar.Region.KR):
        return
    at = now()
    if not (_FUTURES_START <= at.time() <= _FUTURES_END):
        return
    for market in Market:
        investors = kis_futures.fetch_investors(market)
        if investors is None:
            continue
        try:
            with get_session_factory()() as session:
                application.record_futures_investors(session, market, today(), at, investors)
        except Exception:
            log.warning("선물 투자자 스냅샷 적재 실패 (market=%s)", market, exc_info=True)


_QUOTE_JOB = "market-quote-poller"


def _domestic_open(at: datetime, start: time, end: time) -> bool:
    return start <= at.time() <= end and not calendar.is_holiday(calendar.Region.KR)


def _night_open(at: datetime) -> bool:
    # 자정 뒤에도 전날 시작한 세션이다. 금요일 밤→토요일 새벽을 포함한다.
    if not (at.time() >= time(18) or at.time() <= time(6, 2)):
        return False
    day = at.date() if at.time() >= time(18) else at.date() - timedelta(days=1)
    opened = calendar.is_open(day)
    return opened if opened is not None else day.weekday() < 5


def _us_cash_open(at: datetime) -> bool:
    local = at.replace(tzinfo=KST).astimezone(calendar.Region.US.zone)
    # 지연 시세의 마지막 값도 받도록 정규장 마감 뒤 15분을 더 갱신한다.
    return time(9, 30) <= local.time() <= time(16, 15) and not calendar.is_holiday(calendar.Region.US)


def _global_open(at: datetime) -> bool:
    # 선물·FX는 주식 휴장일과 다르다. 주말은 쉬고 조기 마감·상품별 휴일은 공급자 값을 따른다.
    local = at.replace(tzinfo=KST).astimezone(calendar.Region.US.zone)
    weekday, clock = local.weekday(), local.time()
    if weekday == 5 or (weekday == 6 and clock < time(18)):
        return False
    if weekday == 4 and clock > time(17, 15):
        return False
    return True


@tracing.traced_job(_QUOTE_JOB)
def poll_quotes() -> None:
    """요청자 수와 무관하게 시세를 채운다. 개장 전에는 마지막 정상값을 계속 제공한다."""
    at = now()
    targets = [
        (application.get_kospi, (), lambda: _domestic_open(at, time(9), time(15, 42))),
        (application.get_kosdaq, (), lambda: _domestic_open(at, time(9), time(15, 42))),
        (application.futures_quote, (Market.KOSPI,), lambda: _domestic_open(at, time(8, 45), time(15, 47))),
        (application.futures_quote, (Market.KOSDAQ,), lambda: _domestic_open(at, time(8, 45), time(15, 47))),
        (application.night_futures_quote, (), lambda: _night_open(at)),
        (application._quote_of, (application.NASDAQ_SYMBOL,), lambda: _us_cash_open(at)),
        (application._quote_of, (application.NASDAQ_FUTURES_SYMBOL,), lambda: _global_open(at)),
        (application._quote_of, (application.MACRO_SYMBOLS["USD_KRW"],), lambda: _global_open(at)),
        (application._quote_of, (application.MACRO_SYMBOLS["WTI"],), lambda: _global_open(at)),
        (application._quote_of, (application.MACRO_SYMBOLS["VIX"],), lambda: _us_cash_open(at)),
        (application._quote_of, (application.MACRO_SYMBOLS["US10Y"],), lambda: _us_cash_open(at)),
    ]
    for fetch, args, active in targets:
        try:
            # 재기동 직후엔 장 밖이어도 한 번 채운다. 빈 값은 다음 회차에 재시도한다.
            if fetch.peek(*args) is None or active():
                if fetch.refresh(*args) is None:
                    metrics.job_failed(_QUOTE_JOB)
        except Exception:
            metrics.job_failed(_QUOTE_JOB)
            log.warning("시세 갱신 실패 (%s %s) — 마지막 정상값 유지", fetch.__name__, args, exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        poll_quotes,
        IntervalTrigger(seconds=30),
        id=_QUOTE_JOB,
        replace_existing=True,
        max_instances=1,
        coalesce=True,
        next_run_time=now().replace(tzinfo=KST),
    )
    scheduler.add_job(
        poll_futures_investors,
        IntervalTrigger(seconds=60),
        id=_JOB,
        replace_existing=True,
    )
