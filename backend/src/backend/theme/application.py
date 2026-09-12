"""테마 캘린더 — 캡처(적재)와 조회."""

import logging
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from backend.library.time import now
from backend.market import calendar
from backend.platform.kiwoom import market as kiwoom_market
from backend.platform.kiwoom import theme as kiwoom_theme
from backend.theme.domain import ThemeDailyRecord, ThemeDailyStock

log = logging.getLogger(__name__)

_CAPTURE_LIMIT = 12        # 하루 저장 테마 수
_CAPTURE_STOCK_COUNT = 40  # 거래대금 상위 N종목을 테마로 집계
_SESSION_START = time(8, 0)  # 프리마켓 개장 — 이 시각 후 당일 데이터 존재


@dataclass(frozen=True)
class _Contributor:
    code: str
    name: str
    trading_value: int
    price_change_rate: float


@dataclass(frozen=True)
class ThemeWithStocks:
    record: ThemeDailyRecord
    stocks: list[ThemeDailyStock]


def capture(session: Session, at: datetime | None = None) -> int:
    """거래대금 상위 종목의 거래대금을 소속 테마별로 합산해 상위 N개와 기여 종목을 적재.

    같은 날 재실행 시 교체한다. 반환값은 저장한 테마 수.
    """
    capture_date = _most_recent_trading_day_with_data(at or now())

    by_theme: dict[str, list[_Contributor]] = {}
    for stock in kiwoom_market.fetch_top_trading_value_stocks(_CAPTURE_STOCK_COUNT):
        if stock.accumulated_trading_value <= 0:
            continue
        for name in kiwoom_theme.fetch_themes_for_stock(stock.stock_code):
            by_theme.setdefault(name, []).append(
                _Contributor(
                    stock.stock_code,
                    stock.stock_name,
                    stock.accumulated_trading_value,
                    stock.price_change_rate,
                )
            )

    if not by_theme:
        log.warning("테마 캡처 — 집계할 테마 없음, 저장 생략 (date=%s)", capture_date)
        return 0

    ranked = sorted(
        by_theme.items(), key=lambda kv: sum(c.trading_value for c in kv[1]), reverse=True
    )[:_CAPTURE_LIMIT]

    # 유니크(date, theme_name) 충돌 방지 — 재적재 전에 삭제를 먼저 반영한다.
    session.execute(delete(ThemeDailyStock).where(ThemeDailyStock.date == capture_date))
    session.execute(delete(ThemeDailyRecord).where(ThemeDailyRecord.date == capture_date))
    session.flush()

    stamp = now()
    parents = [
        ThemeDailyRecord(
            date=capture_date,
            rank=i + 1,
            theme_name=name,
            trading_value=sum(c.trading_value for c in contribs),
            created_at=stamp,
            updated_at=stamp,
        )
        for i, (name, contribs) in enumerate(ranked)
    ]
    session.add_all(parents)
    session.flush()  # id 확보

    children = []
    for parent, (_, contribs) in zip(parents, ranked, strict=True):
        for c in sorted(contribs, key=lambda c: c.trading_value, reverse=True):
            children.append(
                ThemeDailyStock(
                    theme_daily_id=parent.id,
                    date=capture_date,
                    stock_code=c.code,
                    stock_name=c.name,
                    trading_value=c.trading_value,
                    price_change_rate=c.price_change_rate,
                    created_at=stamp,
                    updated_at=stamp,
                )
            )
    session.add_all(children)
    session.commit()

    log.info(
        "테마 캡처 완료 — 테마 %d건, 종목 %d건 (date=%s)", len(parents), len(children), capture_date
    )
    return len(parents)


def get_calendar(session: Session, from_: date, to: date) -> list[ThemeWithStocks]:
    """기간 내 일자별 상위 테마.

    휴장일에 잘못 캡처된 레코드는 숨긴다(폴러가 공휴일 인식 전 쌓았을 수 있음).
    판정 불가한 과거는 그대로 둔다.
    """
    records = [
        r
        for r in session.scalars(
            select(ThemeDailyRecord)
            .where(ThemeDailyRecord.date.between(from_, to))
            .order_by(ThemeDailyRecord.date.asc(), ThemeDailyRecord.rank.asc())
        )
        if calendar.is_open(r.date) is not False
    ]
    if not records:
        return []

    stocks = session.scalars(
        select(ThemeDailyStock)
        .where(ThemeDailyStock.theme_daily_id.in_([r.id for r in records]))
        .order_by(ThemeDailyStock.trading_value.desc())
    )
    by_parent: dict[int, list[ThemeDailyStock]] = {}
    for s in stocks:
        by_parent.setdefault(s.theme_daily_id, []).append(s)

    return [ThemeWithStocks(r, by_parent.get(r.id, [])) for r in records]


def _most_recent_trading_day_with_data(at: datetime) -> date:
    """당일 장 데이터가 존재하는 가장 최근 거래일.

    프리마켓 시작(08:00) 전 새벽에 캡처하면 아직 당일 데이터가 없어 직전 거래일로 저장한다 —
    **전일 종가 기준 거래대금이 당일 날짜로 잘못 들어가는 걸 막는다.**
    """
    d = at.date()
    is_weekend = d.weekday() >= 5
    if not is_weekend and at.time() >= _SESSION_START:
        return d
    d -= timedelta(days=1)
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d
