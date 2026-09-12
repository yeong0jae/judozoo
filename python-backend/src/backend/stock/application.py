"""종목 카탈로그 — 메모리 적재 / DB 영속 / 검색.

검색이 매 호출 DB를 때리지 않도록 카탈로그를 통째로 메모리에 들고 있다.
교체는 항상 전체 교체라 모듈 전역 하나를 갈아끼우면 된다(할당은 원자적 —
Kotlin `@Volatile` 필드에 대응). 인스턴스가 1대라 공유 캐시를 두지 않는다.
"""

import logging
from dataclasses import dataclass
from datetime import date

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from backend.library.cache import is_empty, ttl_cache
from backend.library.db import get_session_factory
from backend.platform.kiwoom import investor as kiwoom_investor
from backend.stock import infrastructure
from backend.stock.domain import OverseasStock, Stock, Stocks

log = logging.getLogger(__name__)

SEARCH_LIMIT = 20

_domestic = Stocks([])
_overseas: list[OverseasStock] = []


@dataclass(frozen=True)
class StockSearchResult:
    """`exchange`가 None이면 국내 종목. 해외는 NAS/NYS/AMS."""

    stock_code: str
    stock_name: str
    exchange: str | None


def search(query: str) -> list[StockSearchResult]:
    """국내(코스피/코스닥) + 해외(NAS/NYS/AMS) 통합 검색."""
    domestic = [
        StockSearchResult(stock_code=s.short_code, stock_name=s.name, exchange=None)
        for s in _domestic.search(query, SEARCH_LIMIT)
    ]
    q = query.strip().lower()
    overseas = (
        [
            StockSearchResult(stock_code=s.symbol, stock_name=s.name, exchange=s.exchange)
            for s in _overseas
            if s.matches(q)
        ][:SEARCH_LIMIT]
        if q
        else []
    )
    return (domestic + overseas)[:SEARCH_LIMIT]


def catalog_sizes() -> tuple[int, int]:
    """(국내, 해외) 적재 건수 — 로그·검증용."""
    return len(_domestic), len(_overseas)


# --- 영속 ---------------------------------------------------------------


def _last_synced_date(session: Session, model: type) -> date | None:
    """가장 최근 row의 생성일 = 마지막 갱신일. 비어 있으면 None."""
    latest = session.scalar(select(model.created_at).order_by(model.created_at.desc()).limit(1))
    return latest.date() if latest is not None else None


def _replace_all(session: Session, model: type, rows: list) -> None:
    session.execute(delete(model))
    session.add_all(rows)
    session.commit()


# --- 갱신 ---------------------------------------------------------------


def refresh(today: date) -> None:
    """국내·해외 카탈로그를 메모리에 적재한다.

    오늘 이미 동기화됐으면 DB에서 그대로 적재(다운로드 생략), 아니면 마스터 파일을
    받아 전체 교체 후 적재. 다운로드 실패는 fail-soft — 기동을 막지 않고
    DB에 남은 직전 데이터라도 올린다.
    """
    _refresh_domestic(today)
    _refresh_overseas(today)


def _refresh_domestic(today: date) -> None:
    global _domestic
    try:
        with get_session_factory()() as session:
            if _last_synced_date(session, Stock) == today:
                cached = list(session.scalars(select(Stock)))
                _domestic = Stocks(cached)
                log.info("종목 카탈로그 — 오늘 이미 동기화됨, DB에서 %d건 적재", len(cached))
                return
            fetched = infrastructure.fetch_domestic()
            _replace_all(session, Stock, fetched)
            _domestic = Stocks(fetched)
            log.info("종목 카탈로그 갱신 완료 — %d건", len(fetched))
    except Exception:
        log.warning("종목 카탈로그 갱신 실패 — DB의 직전 데이터로 폴백", exc_info=True)
        try:
            with get_session_factory()() as session:
                _domestic = Stocks(list(session.scalars(select(Stock))))
        except Exception:
            log.warning("직전 데이터 적재도 실패", exc_info=True)


def _refresh_overseas(today: date) -> None:
    global _overseas
    try:
        with get_session_factory()() as session:
            if _last_synced_date(session, OverseasStock) == today:
                cached = list(session.scalars(select(OverseasStock)))
                _overseas = cached
                log.info("해외 종목 카탈로그 — 오늘 이미 동기화됨, DB에서 %d건 적재", len(cached))
                return
            fetched = infrastructure.fetch_overseas()
            _replace_all(session, OverseasStock, fetched)
            _overseas = fetched
            log.info("해외 종목 카탈로그 갱신 완료 — %d건", len(fetched))
    except Exception:
        log.warning("해외 종목 카탈로그 갱신 실패 — DB의 직전 데이터로 폴백", exc_info=True)
        try:
            with get_session_factory()() as session:
                _overseas = list(session.scalars(select(OverseasStock)))
        except Exception:
            log.warning("직전 데이터 적재도 실패", exc_info=True)


# --- 종목 투자자 수급 -----------------------------------------------------


@dataclass(frozen=True)
class StockOrgBreakdown:
    """기관 세부 순매수(백만원) — 시장 수급 표와 같은 7종."""

    financial_investment_million: int
    trust_million: int
    pension_fund_million: int
    private_equity_million: int
    insurance_million: int
    bank_million: int
    other_finance_million: int


@dataclass(frozen=True)
class StockInvestorDay:
    """하루치 종목 투자자 순매수(백만원)."""

    date: date
    individual_million: int
    foreign_million: int
    institution_million: int
    other_corp_million: int
    breakdown: StockOrgBreakdown


@ttl_cache(
    "stockInvestorDaily",
    ttl_seconds=60,
    maxsize=100,
    key=lambda stock_code, count: f"{stock_code}:{count}",
    skip_if=is_empty,
)
def investor_daily_history(stock_code: str, count: int) -> list[StockInvestorDay]:
    """키움 ka10059. **백만원 단위를 그대로 준다** — 종목 단위는 억으로 반올림하면
    작은 수급(기관 세부 등)이 0으로 뭉개진다. 표시 단위는 각 화면이 결정한다.
    """
    out = []
    for day in kiwoom_investor.fetch_investor_trend(stock_code)[:count]:
        parsed = _parse_iso_date(day.date)
        if parsed is None:
            continue
        out.append(
            StockInvestorDay(
                date=parsed,
                individual_million=day.individual_net,
                foreign_million=day.foreign_net,
                institution_million=day.institution_net,
                other_corp_million=day.other_corp_net,
                breakdown=StockOrgBreakdown(
                    financial_investment_million=day.financial_investment_net,
                    trust_million=day.trust_net,
                    pension_fund_million=day.pension_fund_net,
                    private_equity_million=day.private_equity_net,
                    insurance_million=day.insurance_net,
                    bank_million=day.bank_net,
                    other_finance_million=day.other_finance_net,
                ),
            )
        )
    return out


def _parse_iso_date(value: str) -> date | None:
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None
