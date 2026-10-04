"""주도주 타임라인 API.

하루는 약 700분 × 5종목이라, 종목 이름·코드는 사전으로 한 번만 싣고 분마다 번호만 보낸다.
"""

from datetime import date, datetime, time
from typing import Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.leadertimeline import application
from backend.library.db import get_db
from backend.library.web import ApiResponse
from backend.market.calendar import Region

router = APIRouter(prefix="/api/leader-timeline")

_REGIONS = {"kr": Region.KR, "us": Region.US}


class TimelineStock(BaseModel):
    exchange: str | None
    code: str
    #: 그날 마지막으로 찍힌 이름
    name: str


class TimelineTick(BaseModel):
    """한 분. `stocks`는 종목 사전의 번호(순위 순) — 비어 있으면 그 분엔 주도주가 없었다."""

    at: str  # 현지 HH:MM
    stocks: list[int]
    rates: list[float]
    values: list[float]


class TimelineResponse(BaseModel):
    stocks: list[TimelineStock]
    ticks: list[TimelineTick]
    #: 그날 마지막으로 찍은 실제 시각(KST ISO). 화면의 "47초 전"
    last_taken_at: datetime | None = Field(serialization_alias="lastTakenAt")


@router.get("")
def get_day(
    market: Literal["kr", "us"] = Query(),
    date_: date = Query(alias="date"),
    since: str | None = Query(None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$"),
    db: Session = Depends(get_db),
) -> ApiResponse[TimelineResponse]:
    """`market=kr&date=2026-09-23`. 날짜·시각은 그 시장의 현지 기준 — 해외는 뉴욕.
    `since=10:59`면 그 **뒤** 분만(장중에 새 분만 받아 붙일 때)."""
    region = _REGIONS[market]
    after = datetime.combine(date_, time.fromisoformat(since)) if since else None
    ticks = application.find_day(db, region, date_, after)

    index: dict[tuple[str | None, str], int] = {}
    names: dict[int, str] = {}
    items = []
    for t in ticks:
        ids = []
        for s in t.stocks:
            key = (s.exchange, s.code)
            if key not in index:
                index[key] = len(index)
            names[index[key]] = s.name
            ids.append(index[key])
        items.append(TimelineTick(
            at=t.at.strftime("%H:%M"),
            stocks=ids,
            rates=[s.change_rate for s in t.stocks],
            values=[float(s.trading_value) for s in t.stocks],
        ))
    stocks = [TimelineStock(exchange=ex, code=code, name=names[i]) for (ex, code), i in index.items()]
    return ApiResponse.ok(TimelineResponse(
        stocks=stocks, ticks=items, last_taken_at=ticks[-1].taken_at if ticks else None,
    ))
