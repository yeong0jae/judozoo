"""시황 폴러 — 선물 투자자(60초) / 프로그램 매매(120초).

둘 다 "당일 누적"을 스냅샷으로 찍어두는 게 목적이다. 세션별 순매수는 이 스냅샷들의
경계 diff로 계산되므로, 폴러가 돌지 않은 과거는 **소급이 불가능**하다.
"""

import logging
from datetime import time

from apscheduler.schedulers.base import BaseScheduler
from apscheduler.triggers.interval import IntervalTrigger

from backend.library.db import get_session_factory
from backend.library.time import now, today
from backend.market import application, calendar
from backend.platform.kis import futures as kis_futures
from backend.platform.kiwoom import program as kiwoom_program
from backend.stock.domain import Market

log = logging.getLogger(__name__)

_FUTURES_START = time(8, 45)   # 선물 개장
_FUTURES_END = time(15, 45)    # 선물 마감
_PROGRAM_START = time(8, 0)
_PROGRAM_END = time(20, 0)


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


def poll_program_trade() -> None:
    if calendar.is_holiday(calendar.Region.KR):
        return
    at = now()
    if not (_PROGRAM_START <= at.time() <= _PROGRAM_END):
        return
    current = today()
    for market in Market:
        points = kiwoom_program.fetch_market_program_daily(market, current)
        todays = next((p for p in points if p.date == current), None)
        if todays is None:
            continue
        try:
            with get_session_factory()() as session:
                application.record_program(session, market, current, at, todays)
        except Exception:
            log.warning("프로그램 스냅샷 적재 실패 (%s)", market, exc_info=True)


def register(scheduler: BaseScheduler) -> None:
    scheduler.add_job(
        poll_futures_investors,
        IntervalTrigger(seconds=60),
        id="futures-investor-poller",
        replace_existing=True,
    )
    scheduler.add_job(
        poll_program_trade,
        IntervalTrigger(seconds=120),
        id="program-trade-poller",
        replace_existing=True,
    )
