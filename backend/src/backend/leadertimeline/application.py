"""주도주 타임라인 — 1분 스냅샷과 하루 조회.

무엇이 주도주인지는 홈 카드가 정한다. 여기서는 그 답을 1분마다 받아 남긴다.
풀은 장중 15초마다 갈아 끼워지는 캐시라 브로커를 새로 부르지 않는다.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from backend.leadertimeline.entities import LeaderTick, LeaderTickStock
from backend.leadingstock import application as leadingstock
from backend.market.calendar import Region
from backend.overseasleadingstock import application as overseasleadingstock

log = logging.getLogger(__name__)

#: 홈 주도주 카드의 줄 수와 같다(캘린더와 같은 이유로 여기 한 번 더 적는다)
LEADERS_COUNT = 5


@dataclass(frozen=True)
class RecordedTick:
    at: datetime
    stocks: list[LeaderTickStock]


def snapshot(session: Session, region: Region, at: datetime, taken_at: datetime) -> int:
    """그 분의 주도주를 남긴다. 풀 조회가 실패하면 예외가 그대로 올라가고 아무것도 남지 않는다 —
    0개로 남기면 브로커 오류가 "주도주 없음"으로 영구히 남는다."""
    if region is Region.KR:
        stocks = [LeaderTickStock.domestic(i + 1, s) for i, s in enumerate(leadingstock.find_leaders(LEADERS_COUNT))]
    else:
        stocks = [LeaderTickStock.overseas(i + 1, s) for i, s in enumerate(overseasleadingstock.get_leaders(LEADERS_COUNT))]
    _record(session, region, at, taken_at, stocks)
    return len(stocks)


def _record(session: Session, region: Region, at: datetime, taken_at: datetime, stocks: list[LeaderTickStock]) -> None:
    """같은 (시장, 분)이 있으면 지우고 새로 쓴다 — 재기동·두 인스턴스가 한 벌만 남긴다."""
    old = session.scalars(select(LeaderTick.id).where(LeaderTick.region == region, LeaderTick.at == at)).all()
    if old:
        session.execute(delete(LeaderTickStock).where(LeaderTickStock.leader_tick_id.in_(old)))
        session.execute(delete(LeaderTick).where(LeaderTick.id.in_(old)))
    tick = LeaderTick(region=region, trade_date=at.date(), at=at, created_at=taken_at)
    session.add(tick)
    session.flush()
    for s in stocks:
        s.leader_tick_id = tick.id
    session.add_all(stocks)
    session.commit()


def find_day(session: Session, region: Region, trade_date: date, since: datetime | None = None) -> list[RecordedTick]:
    """그날의 분들, 시각 순. `since`가 있으면 그 **뒤** 분만 — 장중에 새 분만 받아 붙일 때 쓴다."""
    query = select(LeaderTick).where(LeaderTick.region == region, LeaderTick.trade_date == trade_date)
    if since is not None:
        query = query.where(LeaderTick.at > since)
    ticks = session.scalars(query.order_by(LeaderTick.at)).all()
    if not ticks:
        return []
    stocks = session.scalars(
        select(LeaderTickStock)
        .where(LeaderTickStock.leader_tick_id.in_([t.id for t in ticks]))
        .order_by(LeaderTickStock.rank)
    ).all()
    by_tick: dict[int, list[LeaderTickStock]] = {t.id: [] for t in ticks}
    for s in stocks:
        by_tick[s.leader_tick_id].append(s)
    return [RecordedTick(t.at, by_tick[t.id]) for t in ticks]


def latest(session: Session, region: Region, trade_date: date) -> RecordedTick | None:
    """그날 마지막 분. 캘린더가 마감 기록 전의 오늘 칸을 채울 때 쓴다."""
    tick = session.scalars(
        select(LeaderTick)
        .where(LeaderTick.region == region, LeaderTick.trade_date == trade_date)
        .order_by(LeaderTick.at.desc())
        .limit(1)
    ).first()
    if tick is None:
        return None
    stocks = session.scalars(
        select(LeaderTickStock).where(LeaderTickStock.leader_tick_id == tick.id).order_by(LeaderTickStock.rank)
    ).all()
    return RecordedTick(tick.at, list(stocks))


def last_taken_at(session: Session, region: Region, trade_date: date) -> datetime | None:
    """그날 마지막으로 찍은 실제 시각(KST). 화면의 "47초 전"이 여기서 나온다."""
    return session.scalars(
        select(LeaderTick.created_at)
        .where(LeaderTick.region == region, LeaderTick.trade_date == trade_date)
        .order_by(LeaderTick.at.desc())
        .limit(1)
    ).first()
