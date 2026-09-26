"""주도주 캘린더 API."""

from datetime import date

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.leadercalendar import application
from backend.leadercalendar.application import RecordedDay
from backend.library.db import get_db
from backend.library.web import ApiResponse

router = APIRouter(prefix="/api/leader-calendar")


class LeaderStockItem(BaseModel):
    rank: int
    exchange: str | None
    code: str
    name: str
    price: float
    change_rate: float = Field(serialization_alias="changeRate")
    trading_value: float = Field(serialization_alias="tradingValue")


class LeaderDayItem(BaseModel):
    """`closed`면 휴장. 아니고 `stocks`가 비어 있으면 그날은 주도주가 없었다. 날 자체가 없으면 기록이 없다."""

    date: date
    closed: bool
    stocks: list[LeaderStockItem]


class LeaderCalendarResponse(BaseModel):
    """국내·해외를 따로 준다. 날짜는 각 시장의 현지 거래일 — 짝짓기는 화면이 한다."""

    domestic: list[LeaderDayItem]
    overseas: list[LeaderDayItem]


@router.get("")
def get_month(
    month: str = Query(pattern=r"^\d{4}-(0[1-9]|1[0-2])$"),
    db: Session = Depends(get_db),
) -> ApiResponse[LeaderCalendarResponse]:
    """`month=2026-09`. 해외는 그달 1일 직전 평일부터 온다."""
    year, mon = (int(v) for v in month.split("-"))
    domestic, overseas = application.find_month(db, year, mon)
    return ApiResponse.ok(LeaderCalendarResponse(domestic=_items(domestic), overseas=_items(overseas)))


def _items(days: list[RecordedDay]) -> list[LeaderDayItem]:
    return [
        LeaderDayItem(
            date=d.trade_date,
            closed=d.closed,
            stocks=[
                LeaderStockItem(
                    rank=s.rank, exchange=s.exchange, code=s.code, name=s.name,
                    price=float(s.price), change_rate=s.change_rate, trading_value=float(s.trading_value),
                )
                for s in d.stocks
            ],
        )
        for d in days
    ]
