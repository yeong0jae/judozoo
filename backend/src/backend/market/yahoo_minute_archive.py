"""야후 지수·매크로 지표의 완성된 지난 미국 거래 세션 분봉을 DB에서 재사용한다."""

import logging
from dataclasses import asdict
from datetime import date

from sqlalchemy import select
from sqlalchemy.dialects.mysql import insert

from backend.library.db import get_session_factory
from backend.market.domain import YahooMinuteDay
from backend.platform.yahoo.client import YahooBar

log = logging.getLogger(__name__)


def recent(symbol: str, through: date, count: int = 2) -> dict[date, list[YahooBar]]:
    try:
        with get_session_factory()() as session:
            rows = session.scalars(
                select(YahooMinuteDay)
                .where(YahooMinuteDay.symbol == symbol, YahooMinuteDay.trade_date <= through)
                .order_by(YahooMinuteDay.trade_date.desc())
                .limit(count)
            )
            return {row.trade_date: [YahooBar(**bar) for bar in row.candles] for row in rows}
    except Exception:
        log.warning("야후 분봉 DB 조회 실패 symbol=%s", symbol, exc_info=True)
        return {}


def put(symbol: str, day: date, bars: list[YahooBar]) -> None:
    if not bars:
        return
    payload = [asdict(bar) for bar in sorted(bars, key=lambda bar: bar.date + bar.time)]
    try:
        with get_session_factory()() as session:
            stmt = insert(YahooMinuteDay).values(symbol=symbol, trade_date=day, candles=payload)
            session.execute(stmt.on_duplicate_key_update(candles=stmt.inserted.candles))
            session.commit()
    except Exception:
        log.warning("야후 분봉 DB 저장 실패 symbol=%s day=%s", symbol, day, exc_info=True)
