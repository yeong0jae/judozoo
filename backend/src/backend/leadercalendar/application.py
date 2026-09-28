"""주도주 캘린더 — 마감 스냅샷과 월 조회.

무엇이 주도주인지는 홈 카드가 정한다. 여기서는 그 답을 마감 때 한 번 받아 남긴다.
마감 기록 전의 오늘은 타임라인이 매분 남긴 마지막 분으로 채워 보인다 — 같은 답을 같은 풀에서 받은 것이다.
"""

import logging
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from backend.leadercalendar.domain import previous_weekday
from backend.leadercalendar.entities import LeaderDay, LeaderDayStock
from backend.leadertimeline import application as leadertimeline
from backend.leadertimeline.entities import LeaderTickStock
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
    stocks: Sequence[LeaderDayStock | LeaderTickStock]
    closed: bool = False
    #: 마감 기록 전의 오늘 — 타임라인의 마지막 분이라 순위가 아직 바뀐다
    live: bool = False


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


def record_closed(session: Session, region: Region, trade_date: date, at: datetime) -> None:
    """휴장일을 남긴다 — 날 행만 있고 종목은 없다. 캘린더가 "기록 없음"과 휴장을 가를 수 있게."""
    _record(session, region, trade_date, at, [], closed=True)


def _record(
    session: Session, region: Region, trade_date: date, at: datetime, stocks: list[LeaderDayStock],
    closed: bool = False,
) -> None:
    """같은 (시장, 거래일)이 있으면 지우고 새로 쓴다 — 재기동·수동 재실행·두 인스턴스가 한 벌만 남긴다."""
    old = session.scalars(
        select(LeaderDay.id).where(LeaderDay.region == region, LeaderDay.trade_date == trade_date)
    ).all()
    if old:
        session.execute(delete(LeaderDayStock).where(LeaderDayStock.leader_day_id.in_(old)))
        session.execute(delete(LeaderDay).where(LeaderDay.id.in_(old)))

    day = LeaderDay.taken(region, trade_date, at, closed)
    session.add(day)
    session.flush()
    for s in stocks:
        s.leader_day_id = day.id
    session.add_all(stocks)
    session.commit()
    log.info("주도주 캘린더 %s %s — %s", region.value, trade_date, "휴장" if closed else f"{len(stocks)}종목")


def find_month(session: Session, year: int, month: int) -> tuple[list[RecordedDay], list[RecordedDay]]:
    """(국내 날들, 해외 날들). 해외는 **그달 1일 직전 평일부터** — 1일 칸에 붙는 해외장이 전달에 있다."""
    first = date(year, month, 1)
    after = date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)
    us_start = previous_weekday(first)
    return (
        _with_today(session, Region.KR, _days(session, Region.KR, first, after), first, after),
        _with_today(session, Region.US, _days(session, Region.US, us_start, after), us_start, after),
    )


def _with_today(session: Session, region: Region, days: list[RecordedDay], start: date, end: date) -> list[RecordedDay]:
    """마감 기록이 아직 없는 오늘을 타임라인의 마지막 분으로 채운다. 타임라인은 장 시간·휴장일을 이미 걸러
    찍으므로, 장 전에 풀이 들고 있는 어제 값이 오늘 칸에 들어오지 않는다."""
    today = region.today()
    if not (start <= today < end) or any(d.trade_date == today for d in days):
        return days
    tick = leadertimeline.latest(session, region, today)
    if tick is None:
        return days
    return [*days, RecordedDay(today, tick.stocks, live=True)]


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
    return [RecordedDay(d.trade_date, by_day[d.id], d.closed) for d in days]
