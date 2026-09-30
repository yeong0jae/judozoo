"""지수의 완성된 지난 거래일 분봉을 DB에 보관한다. DB 장애 시 외부 조회로 폴백한다."""

import logging
from dataclasses import asdict
from datetime import date, datetime

from sqlalchemy.dialects.mysql import insert

from backend.library.db import get_session_factory
from backend.market.domain import IndexMinuteDay
from backend.platform.toss.market_indicator import TossCandle
from backend.stock.domain import Market

log = logging.getLogger(__name__)


def get(market: Market, day: date) -> list[TossCandle] | None:
    try:
        with get_session_factory()() as session:
            stored = session.get(IndexMinuteDay, (market, day))
            if stored is None:
                return None
            return [TossCandle(**{**c, "timestamp": datetime.fromisoformat(c["timestamp"])})
                    for c in stored.candles]
    except Exception:
        log.warning("지수 분봉 DB 조회 실패 market=%s day=%s", market, day, exc_info=True)
        return None


def put(market: Market, day: date, bars: list[TossCandle]) -> None:
    if not bars:
        return
    payload = [{**asdict(c), "timestamp": c.timestamp.isoformat()}
               for c in sorted(bars, key=lambda c: c.timestamp)]
    try:
        with get_session_factory()() as session:
            stmt = insert(IndexMinuteDay).values(market=market, trade_date=day, candles=payload)
            session.execute(stmt.on_duplicate_key_update(candles=stmt.inserted.candles))
            session.commit()
    except Exception:
        log.warning("지수 분봉 DB 저장 실패 market=%s day=%s", market, day, exc_info=True)
