"""테마 캘린더 API."""

from datetime import date

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.library.db import get_db
from backend.library.web import ApiResponse
from backend.theme import application

router = APIRouter(prefix="/api/themes")


class ThemeStockItem(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    trading_value: int = Field(serialization_alias="tradingValue")
    price_change_rate: float | None = Field(serialization_alias="priceChangeRate")


class ThemeItem(BaseModel):
    rank: int
    name: str
    trading_value: int = Field(serialization_alias="tradingValue")
    stocks: list[ThemeStockItem]


class ThemeDayItem(BaseModel):
    date: date
    themes: list[ThemeItem]


class ThemeCalendarResponse(BaseModel):
    days: list[ThemeDayItem]


@router.get("/calendar")
def calendar_(
    from_: date = Query(alias="from"), to: date = Query(), db: Session = Depends(get_db)
) -> ApiResponse[ThemeCalendarResponse]:
    """기간 내 일자별 상위 테마. 캘린더 화면이 한 달 범위로 조회한다."""
    by_date: dict[date, list] = {}
    for tw in application.get_calendar(db, from_, to):
        by_date.setdefault(tw.record.date, []).append(tw)

    days = [
        ThemeDayItem(
            date=on,
            themes=[
                ThemeItem(
                    rank=tw.record.rank,
                    name=tw.record.theme_name,
                    trading_value=tw.record.trading_value,
                    stocks=[
                        ThemeStockItem(
                            stock_code=s.stock_code,
                            stock_name=s.stock_name,
                            trading_value=s.trading_value,
                            price_change_rate=s.price_change_rate,
                        )
                        for s in tw.stocks
                    ],
                )
                for tw in sorted(items, key=lambda t: t.record.rank)
            ],
        )
        for on, items in sorted(by_date.items())
    ]
    return ApiResponse.ok(ThemeCalendarResponse(days=days))


@router.post("/capture")
def capture(db: Session = Depends(get_db)) -> ApiResponse[int]:
    """오늘치 테마를 즉시 캡처 (스케줄 대기 없이 시드/테스트용). 반환=저장 건수."""
    return ApiResponse.ok(application.capture(db))
