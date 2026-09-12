"""시그널 전이·스냅샷 적재/조회.

적재는 폴러가, 조회는 실시간 로그·타임라인 화면이 쓴다.
"""

import logging
from datetime import date, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.leadingstock.entities import (
    IndexMinuteCandleEntity,
    MarketCloseSnapshot,
    MarketFlowStateSnapshot,
    MarketSignalEvent,
    SignalEvent,
)
from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.leadingstock.domain import IndexMinuteCandle
from backend.leadingstock.signals import (
    InvestorFlowState,
    InvestorType,
    MarketSignalType,
    NetTradeSide,
)
from backend.library.time import now
from backend.platform.kiwoom import sector_investor as kiwoom_sector
from backend.stock.domain import Market

log = logging.getLogger(__name__)

MRKT_TP = {Market.KOSPI: "0", Market.KOSDAQ: "1"}


# ── 종목 시그널 ─────────────────────────────────────────────────────────


def record_signal_events(session: Session, events: list[SignalEvent]) -> None:
    if events:
        session.add_all(events)
        session.commit()


def signal_events_on(session: Session, on: date) -> list[SignalEvent]:
    return list(
        session.scalars(
            select(SignalEvent)
            .where(SignalEvent.trade_date == on)
            .order_by(SignalEvent.occurred_at.desc())
        )
    )


# ── 시장 시그널 ─────────────────────────────────────────────────────────


def record_market_events(session: Session, events: list[MarketSignalEvent]) -> None:
    if events:
        session.add_all(events)
        session.commit()


def market_events_on(session: Session, on: date) -> list[MarketSignalEvent]:
    return list(
        session.scalars(
            select(MarketSignalEvent)
            .where(MarketSignalEvent.trade_date == on)
            .order_by(MarketSignalEvent.occurred_at.desc())
        )
    )


def already_fired_net_buy_level(
    session: Session, on: date, market: Market, investor: InvestorType, side: NetTradeSide, level: int
) -> bool:
    """그날 같은 (시장·투자자·방향·단계) 순매수 시그널이 이미 적재됐는지.

    재시작이나 값 회복으로 같은 단계가 다시 울리는 걸 막는다.
    """
    return session.scalar(
        select(MarketSignalEvent.id).where(
            MarketSignalEvent.trade_date == on,
            MarketSignalEvent.kind == MarketSignalType.NET_BUY_LEVEL.value,
            MarketSignalEvent.market == market,
            MarketSignalEvent.investor == investor,
            MarketSignalEvent.side == side,
            MarketSignalEvent.level == level,
        ).limit(1)
    ) is not None


def record_investor_snapshots(session: Session, snapshots: list[MarketInvestorSnapshot]) -> None:
    if snapshots:
        session.add_all(snapshots)
        session.commit()


def investor_net_buy() -> dict[Market, object]:
    """코스피·코스닥 각 시장의 당일 누적 투자자 순매수. 데이터 없는 시장은 제외."""
    out = {}
    for market in Market:
        nb = kiwoom_sector.fetch_sector_net_buy(MRKT_TP[market])
        if nb is not None:
            out[market] = nb
    return out


# ── 흐름 전환 상태 ──────────────────────────────────────────────────────


def load_flow_states(session: Session, on: date) -> dict[str, InvestorFlowState]:
    """그날 흐름 전환 상태 복원 — 키 "market|investor".

    재시작으로 메모리가 비었을 때 정점을 되살려 전환을 놓치지 않게 한다.
    """
    rows = session.scalars(
        select(MarketFlowStateSnapshot).where(MarketFlowStateSnapshot.trade_date == on)
    )
    return {
        f"{r.market.name}|{r.investor.name}": InvestorFlowState(r.side, r.extreme_eok) for r in rows
    }


def save_flow_state(
    session: Session, on: date, market: Market, investor: InvestorType, state: InvestorFlowState
) -> None:
    """(시장, 투자자)별 한 행 upsert. 아직 방향이 없으면(추적 전) 저장하지 않는다."""
    if state.side is None:
        return
    existing = session.scalar(
        select(MarketFlowStateSnapshot).where(
            MarketFlowStateSnapshot.market == market,
            MarketFlowStateSnapshot.investor == investor,
        )
    )
    at = now()
    if existing is not None:
        existing.side = state.side
        existing.extreme_eok = state.extreme_eok
        existing.trade_date = on
        existing.updated_at = at
    else:
        session.add(
            MarketFlowStateSnapshot(
                market=market, investor=investor, side=state.side,
                extreme_eok=state.extreme_eok, trade_date=on,
                created_at=at, updated_at=at,
            )
        )
    session.commit()


# ── 마감 스냅샷 ─────────────────────────────────────────────────────────


def capture_close_snapshots(session: Session, on: date, captured_at: datetime) -> int:
    """코스피·코스닥 마감 투자자 순매수를 한 행씩 적재.

    이미 적재된 시장은 스킵(멱등), 데이터 없는 시장도 스킵. 적재한 시장 수를 돌려준다.
    """
    saved = 0
    at = now()
    for market in Market:
        exists = session.scalar(
            select(MarketCloseSnapshot.id).where(
                MarketCloseSnapshot.market == market, MarketCloseSnapshot.trade_date == on
            ).limit(1)
        )
        if exists is not None:
            continue
        nb = kiwoom_sector.fetch_sector_net_buy(MRKT_TP[market])
        if nb is None:
            continue
        session.add(
            MarketCloseSnapshot(
                market=market, trade_date=on, captured_at=captured_at,
                foreign_eok=nb.foreign_eok, institution_eok=nb.institution_eok,
                individual_eok=nb.individual_eok, index_value=nb.index_value,
                change_rate=nb.change_rate, created_at=at, updated_at=at,
            )
        )
        saved += 1
    if saved:
        session.commit()
    return saved


def close_snapshots_on(session: Session, on: date) -> list[MarketCloseSnapshot]:
    return list(
        session.scalars(select(MarketCloseSnapshot).where(MarketCloseSnapshot.trade_date == on))
    )


# ── 지수 1분봉 영속 ─────────────────────────────────────────────────────


def upsert_index_candles(session: Session, market: Market, candles: list[IndexMinuteCandle]) -> None:
    """(시장, 분) 기준 upsert — 진행 중인 분은 더 완성된 값으로 갱신."""
    at = now()
    for c in candles:
        existing = session.scalar(
            select(IndexMinuteCandleEntity).where(
                IndexMinuteCandleEntity.market == market,
                IndexMinuteCandleEntity.minute == c.minute,
            )
        )
        if existing is not None:
            existing.open, existing.high = c.open, c.high
            existing.low, existing.close = c.low, c.close
            existing.volume, existing.updated_at = c.volume, at
        else:
            session.add(
                IndexMinuteCandleEntity(
                    market=market, trade_date=c.minute.date(), minute=c.minute,
                    open=c.open, high=c.high, low=c.low, close=c.close, volume=c.volume,
                    created_at=at, updated_at=at,
                )
            )
    session.commit()


def index_candles_in_range(
    session: Session, market: Market, from_: date, to: date
) -> list[IndexMinuteCandle]:
    rows = session.scalars(
        select(IndexMinuteCandleEntity)
        .where(
            IndexMinuteCandleEntity.market == market,
            IndexMinuteCandleEntity.trade_date.between(from_, to),
        )
        .order_by(IndexMinuteCandleEntity.minute.asc())
    )
    return [
        IndexMinuteCandle(r.minute, r.open, r.high, r.low, r.close, r.volume) for r in rows
    ]


def index_candles_on(session: Session, market: Market, on: date) -> list[IndexMinuteCandle]:
    return index_candles_in_range(session, market, on, on)
