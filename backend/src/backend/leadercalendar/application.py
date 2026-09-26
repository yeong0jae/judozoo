"""주도주 캘린더 — 마감 스냅샷과 월 조회.

무엇이 주도주인지는 홈 카드가 정한다. 여기서는 그 답을 마감 때 한 번 받아 남긴다.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from backend.leadercalendar.domain import previous_weekday
from backend.leadercalendar.entities import LeaderDay, LeaderDayStock
from backend.leadingstock import application as leadingstock
from backend.market.calendar import Region
from backend.overseasleadingstock import application as overseasleadingstock

log = logging.getLogger(__name__)

#: 홈 주도주 카드의 줄 수와 같다(`leadingstock.presentation.LEADERS_COUNT`, 해외도 같은 값).
#: presentation 상수를 application이 가져다 쓰지 않으려고 여기 한 번 더 적는다.
LEADERS_COUNT = 5


@dataclass(frozen=True)
class RecordedDay:
    trade_date: date
    stocks: list[LeaderDayStock]


def snapshot_domestic(session: Session, trade_date: date, at: datetime) -> int:
    """국내 주도주를 `trade_date`로 남긴다. 풀 조회가 실패하면 예외가 그대로 올라가고 아무것도 남지 않는다 —
    0개로 저장하면 브로커 오류가 "주도주 없음"으로 영구히 남는다."""
    leaders = leadingstock.find_leaders(LEADERS_COUNT)
    stocks = [LeaderDayStock.domestic(i + 1, s) for i, s in enumerate(leaders)]
    _record(session, Region.KR, trade_date, at, stocks)
    return len(stocks)


def snapshot_overseas(session: Session, trade_date: date, at: datetime) -> int:
    """해외 주도주를 뉴욕 거래일 `trade_date`로 남긴다. 실패 처리는 국내와 같다."""
    leaders = overseasleadingstock.get_leaders(LEADERS_COUNT)
    stocks = [LeaderDayStock.overseas(i + 1, s) for i, s in enumerate(leaders)]
    _record(session, Region.US, trade_date, at, stocks)
    return len(stocks)


def _record(session: Session, region: Region, trade_date: date, at: datetime, stocks: list[LeaderDayStock]) -> None:
    """같은 (시장, 거래일)이 있으면 지우고 새로 쓴다 — 재기동·수동 재실행·두 인스턴스가 한 벌만 남긴다."""
    old = session.scalars(
        select(LeaderDay.id).where(LeaderDay.region == region, LeaderDay.trade_date == trade_date)
    ).all()
    if old:
        session.execute(delete(LeaderDayStock).where(LeaderDayStock.leader_day_id.in_(old)))
        session.execute(delete(LeaderDay).where(LeaderDay.id.in_(old)))

    day = LeaderDay.taken(region, trade_date, at)
    session.add(day)
    session.flush()
    for s in stocks:
        s.leader_day_id = day.id
    session.add_all(stocks)
    session.commit()
    log.info("주도주 캘린더 %s %s — %d종목", region.value, trade_date, len(stocks))


def find_month(session: Session, year: int, month: int) -> tuple[list[RecordedDay], list[RecordedDay]]:
    """(국내 날들, 해외 날들). 해외는 **그달 1일 직전 평일부터** — 1일 칸에 붙는 해외장이 전달에 있다."""
    first = date(year, month, 1)
    after = date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)
    return (
        _days(session, Region.KR, first, after),
        _days(session, Region.US, previous_weekday(first), after),
    )


def _days(session: Session, region: Region, start: date, end: date) -> list[RecordedDay]:
    days = session.scalars(
        select(LeaderDay)
        .where(LeaderDay.region == region, LeaderDay.trade_date >= start, LeaderDay.trade_date < end)
        .order_by(LeaderDay.trade_date)
    ).all()
    if not days:
        return []
    stocks = session.scalars(
        select(LeaderDayStock)
        .where(LeaderDayStock.leader_day_id.in_([d.id for d in days]))
        .order_by(LeaderDayStock.rank)
    ).all()
    by_day: dict[int, list[LeaderDayStock]] = {d.id: [] for d in days}
    for s in stocks:
        by_day[s.leader_day_id].append(s)
    return [RecordedDay(d.trade_date, by_day[d.id]) for d in days]
