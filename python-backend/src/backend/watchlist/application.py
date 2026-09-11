"""관심 테마 — 테마·종목 추가/삭제/순서.

종목 시세는 `quotes_of`가 따로 물린다. 화면에 보이는 테마의 종목만 조회한다 —
전체 테마를 매번 부르면 키움 rate limit에 걸린다.
"""

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.library.time import now
from backend.platform.kiwoom import market as kiwoom_market
from backend.platform.yahoo import client as yahoo
from backend.watchlist.domain import WatchTheme

_LAST = 10**9  # 재배치에서 "순서 지정 안 됨"을 뒤로 보내는 값


@dataclass(frozen=True)
class WatchStockView:
    stock_code: str
    stock_name: str
    exchange: str | None  # 해외 거래소. None이면 국내


@dataclass(frozen=True)
class WatchThemeView:
    """관심 테마 — 시세는 담기지 않는다(별도 조회)."""

    id: int
    name: str
    stocks: list[WatchStockView]


@dataclass(frozen=True)
class StockQuote:
    """종목 시세 — 관심 테마 리스트 표시용. 해외는 달러, 국내는 원."""

    stock_code: str
    stock_name: str
    current_price: float
    price_change_rate: float
    overseas: bool


def _to_view(theme: WatchTheme) -> WatchThemeView:
    return WatchThemeView(
        id=theme.id,
        name=theme.name,
        stocks=[WatchStockView(s.stock_code, s.stock_name, s.exchange) for s in theme.stocks],
    )


def _all_themes(session: Session) -> list[WatchTheme]:
    return list(session.scalars(select(WatchTheme).order_by(WatchTheme.sort_order.asc())))


def _get_or_raise(session: Session, theme_id: int) -> WatchTheme:
    theme = session.get(WatchTheme, theme_id)
    if theme is None:
        raise ValueError(f"없는 테마입니다: {theme_id}")
    return theme


def find_all(session: Session) -> list[WatchThemeView]:
    return [_to_view(t) for t in _all_themes(session)]


def create_theme(session: Session, name: str) -> WatchThemeView:
    trimmed = _require_name(name)
    if session.scalar(select(WatchTheme.id).where(WatchTheme.name == trimmed)) is not None:
        raise ValueError(f"이미 있는 테마입니다: {trimmed}")
    themes = _all_themes(session)
    next_order = themes[-1].sort_order + 1 if themes else 0
    at = now()
    theme = WatchTheme(name=trimmed, sort_order=next_order, created_at=at, updated_at=at)
    session.add(theme)
    session.commit()
    return _to_view(theme)


def rename_theme(session: Session, theme_id: int, name: str) -> WatchThemeView:
    trimmed = _require_name(name)
    theme = _get_or_raise(session, theme_id)
    if trimmed != theme.name:
        if session.scalar(select(WatchTheme.id).where(WatchTheme.name == trimmed)) is not None:
            raise ValueError(f"이미 있는 테마입니다: {trimmed}")
        theme.name = trimmed
        theme.updated_at = now()
    session.commit()
    return _to_view(theme)


def delete_theme(session: Session, theme_id: int) -> None:
    theme = session.get(WatchTheme, theme_id)
    if theme is not None:
        session.delete(theme)
        session.commit()


def add_stock(
    session: Session, theme_id: int, stock_code: str, stock_name: str, exchange: str | None
) -> WatchThemeView:
    theme = _get_or_raise(session, theme_id)
    theme.add_stock(stock_code, stock_name, exchange, now())
    session.commit()
    return _to_view(theme)


def remove_stock(session: Session, theme_id: int, stock_code: str) -> WatchThemeView:
    theme = _get_or_raise(session, theme_id)
    theme.remove_stock(stock_code)
    session.commit()
    return _to_view(theme)


def reorder_themes(session: Session, ordered_ids: list[int]) -> None:
    """`ordered_ids` 순서대로 테마를 재배치한다."""
    rank = {theme_id: i for i, theme_id in enumerate(ordered_ids)}
    for i, theme in enumerate(sorted(_all_themes(session), key=lambda t: rank.get(t.id, _LAST))):
        theme.sort_order = i
    session.commit()


def reorder_stocks(session: Session, theme_id: int, ordered_codes: list[str]) -> WatchThemeView:
    theme = _get_or_raise(session, theme_id)
    theme.reorder_stocks(ordered_codes)
    session.commit()
    return _to_view(theme)


def quotes_of(session: Session, theme_id: int) -> list[StockQuote]:
    """국내는 키움(ka10001), 해외는 야후(KIS 해외 실시간은 유료시세라 쓰지 않는다)."""
    theme = session.get(WatchTheme, theme_id)
    if theme is None:
        return []
    quotes = []
    for stock in theme.stocks:
        quote = _domestic(stock.stock_code) if stock.exchange is None else _overseas(stock.stock_code)
        if quote is not None:
            quotes.append(quote)
    return quotes


def _domestic(code: str) -> StockQuote | None:
    detail = kiwoom_market.fetch_stock_detail(code)
    if detail is None:
        return None
    return StockQuote(
        stock_code=code,
        stock_name=detail.stock_name,
        current_price=float(detail.current_price),
        price_change_rate=detail.price_change_rate,
        overseas=False,
    )


def _overseas(symbol: str) -> StockQuote | None:
    quote = yahoo.fetch_extended_quote(symbol)
    if quote is None:
        return None
    change = quote.price - quote.prev_close
    return StockQuote(
        stock_code=symbol,
        stock_name=quote.name,
        current_price=quote.price,
        price_change_rate=0.0 if quote.prev_close == 0 else change / quote.prev_close * 100,
        overseas=True,
    )


def _require_name(name: str) -> str:
    trimmed = (name or "").strip()
    if not trimmed:
        raise ValueError("테마 이름이 비어 있습니다")
    return trimmed
