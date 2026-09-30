"""제한폭이 넓은 날(상장 첫날) 표시 — 그날 한 번이라도 +30%를 넘은 종목을 기억한다.

지금 등락률만 보고 환산하면, +100%에서 +26%로 밀려 내려온 신규상장 종목이 환산을 벗어나
"+26% 급등주"로 순위가 튄다. 그래서 넘은 적이 있으면 그날 내내 환산한다.

**메모리 앞, DB(`wide_limit_day`) 뒤** — 재시작해도 잃지 않는다. 날이 바뀐 뒤 처음 볼 때 그날 목록을
DB에서 한 번 올린다. DB가 죽어 있으면 메모리만으로 동작한다 — 그 기록 없이 돌 뿐 화면은 막지 않는다.
"""

import dataclasses
import logging
import threading
from datetime import date, datetime

from sqlalchemy import delete, insert, select

from backend.leadingstock.domain import LeadingStockSnapshot
from backend.leadingstock.entities import WideLimitDay
from backend.library.db import get_session_factory

log = logging.getLogger(__name__)

#: 지난 날 기록은 쓸 데가 없다 — 날이 바뀌어 처음 올릴 때 이보다 오래된 행을 지운다.
_KEEP_DAYS = 7

_lock = threading.Lock()
_day: date | None = None
_codes: set[str] = set()


def _key(stock_code: str) -> str:
    # 랭킹 코드는 `_AL`이 붙어 오고 상세 화면은 맨 코드로 온다 — 같은 종목으로 본다
    return stock_code.split("_")[0]


def mark(stocks: list[LeadingStockSnapshot], day: date, at: datetime) -> list[LeadingStockSnapshot]:
    """지금 +30%를 넘은 종목을 기억하고, 오늘 넘은 적이 있는 종목에 표시를 붙여 돌려준다."""
    codes = _today(day)
    fresh = {_key(s.stock_code) for s in stocks if s.exceeds_normal_limit} - codes
    if fresh:
        with _lock:
            _codes.update(fresh)
        _save(day, fresh, at)
    with _lock:
        known = set(_codes)
    return [dataclasses.replace(s, wide_limit_day=True) if _key(s.stock_code) in known else s for s in stocks]


def _today(day: date) -> set[str]:
    global _day, _codes
    with _lock:
        if _day == day:
            return set(_codes)
    loaded: set[str] = set()
    try:
        with get_session_factory()() as session:
            loaded = set(session.scalars(select(WideLimitDay.stock_code).where(WideLimitDay.trade_date == day)))
            session.execute(delete(WideLimitDay).where(WideLimitDay.trade_date < date.fromordinal(day.toordinal() - _KEEP_DAYS)))
            session.commit()
    except Exception:
        log.warning("제한폭 넓은 날 기록 DB 조회 실패 — 메모리만으로 기억한다", exc_info=True)
    with _lock:
        if _day != day:
            _day, _codes = day, loaded
        return set(_codes)


def _save(day: date, codes: set[str], at: datetime) -> None:
    try:
        with get_session_factory()() as session:
            already = set(session.scalars(
                select(WideLimitDay.stock_code).where(WideLimitDay.trade_date == day, WideLimitDay.stock_code.in_(codes))
            ))
            if codes - already:
                session.execute(insert(WideLimitDay), [
                    {"trade_date": day, "stock_code": c, "created_at": at} for c in sorted(codes - already)
                ])
            session.commit()
    except Exception:
        log.warning("제한폭 넓은 날 기록 DB 저장 실패 (%s)", ", ".join(sorted(codes)), exc_info=True)
    log.info("제한폭 넓은 날 — %s", ", ".join(sorted(codes)))


def reset() -> None:
    """테스트 격리용."""
    global _day
    with _lock:
        _day = None
        _codes.clear()
