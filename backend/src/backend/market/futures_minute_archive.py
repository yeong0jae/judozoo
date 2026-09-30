"""완성된 지난 정규장 거래일·야간 세션 분봉을 계약별로 DB에 보관한다."""

import logging
from dataclasses import asdict
from datetime import date

from sqlalchemy.dialects.mysql import insert

from backend.library.db import get_session_factory
from backend.market.domain import FuturesMinuteDay
from backend.platform.kis.futures import FuturesBar
from backend.stock.domain import Market

log = logging.getLogger(__name__)


def get(market: Market, contract_code: str, night: bool, day: date) -> list[FuturesBar] | None:
    try:
        with get_session_factory()() as session:
            stored = session.get(FuturesMinuteDay, (market, contract_code, night, day))
            return [FuturesBar(**bar) for bar in stored.candles] if stored is not None else None
    except Exception:
        log.warning("선물 분봉 DB 조회 실패 market=%s contract=%s night=%s day=%s",
                    market, contract_code, night, day, exc_info=True)
        return None


def put(market: Market, contract_code: str, night: bool, day: date, bars: list[FuturesBar]) -> None:
    if not bars:
        return
    payload = [asdict(bar) for bar in sorted(bars, key=lambda bar: bar.date + bar.time)]
    try:
        with get_session_factory()() as session:
            stmt = insert(FuturesMinuteDay).values(
                market=market, contract_code=contract_code, night=night, trade_date=day, candles=payload,
            )
            session.execute(stmt.on_duplicate_key_update(candles=stmt.inserted.candles))
            session.commit()
    except Exception:
        log.warning("선물 분봉 DB 저장 실패 market=%s contract=%s night=%s day=%s",
                    market, contract_code, night, day, exc_info=True)
