"""해외 주도주 API — 랭킹 / 지수 마감 스냅샷 / 종목 상세 / 분봉 / 일봉."""

from datetime import date, datetime

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.library.db import get_db
from backend.library.web import ApiResponse
from backend.overseasleadingstock import application
from backend.overseasleadingstock.domain import IndexCloseSnapshot, OverseasStockRank

router = APIRouter(prefix="/api/overseas-leading-stocks")

# 사용자가 -12~7 중 선택. 미지정 시 기본값.
MIN_CHANGE_RATE = -12
MAX_CHANGE_RATE = 7
DEFAULT_MIN_CHANGE_RATE = 7.0

# 한국 시각 기준 오늘 — Kotlin TimeProvider.today()에 대응.
KST_OFFSET_HOURS = 9


class OverseasStockRankItem(BaseModel):
    rank: int
    exchange: str
    symbol: str
    name: str
    ename: str
    price: float
    diff: float
    rate: float
    trading_value: float = Field(serialization_alias="tradingValue")


class FilterResultItem(BaseModel):
    filter_name: str = Field(serialization_alias="filterName")
    criteria_description: str = Field(serialization_alias="criteriaDescription")
    actual_value: str = Field(serialization_alias="actualValue")
    passed: bool


class OverseasSwingHighSignal(BaseModel):
    peak_price: float = Field(serialization_alias="peakPrice")
    peak_at: datetime = Field(serialization_alias="peakAt")
    gap_rate: float = Field(serialization_alias="gapRate")


class OverseasStockDetailResponse(BaseModel):
    exchange: str
    symbol: str
    name: str
    ename: str
    rank: int
    price: float
    diff: float
    rate: float
    trading_value: float = Field(serialization_alias="tradingValue")
    market_cap: int | None = Field(serialization_alias="marketCap")
    filter_results: list[FilterResultItem] = Field(serialization_alias="filterResults")
    swing_high_signal: OverseasSwingHighSignal | None = Field(serialization_alias="swingHighSignal")


class OverseasMinuteCandleItem(BaseModel):
    """가격은 달러(소수), time은 한국 벽시계. 국내 MinuteCandleItem과 동일 JSON 구조."""

    time: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int
    trading_value: float = Field(serialization_alias="tradingValue")


class OverseasDailyCandleItem(BaseModel):
    date: str
    open: float
    high: float
    low: float
    close: float
    volume: int


class OverseasIndexCloseSnapshotItem(BaseModel):
    captured_at: datetime = Field(serialization_alias="capturedAt")
    code: str
    name: str
    index_value: float = Field(serialization_alias="indexValue")
    change_rate: float = Field(serialization_alias="changeRate")


@router.get("/ranking")
def get_ranking(
    minChangeRate: int | None = Query(default=None),  # noqa: N803 — 기존 API 계약
) -> ApiResponse[list[OverseasStockRankItem]]:
    """나스닥·뉴욕·아멕스 통합 거래대금 상위."""
    rate = (
        float(max(MIN_CHANGE_RATE, min(MAX_CHANGE_RATE, minChangeRate)))
        if minChangeRate is not None
        else DEFAULT_MIN_CHANGE_RATE
    )
    return ApiResponse.ok([_to_rank_item(r) for r in application.get_ranking(rate)])


@router.get("/index-close-snapshots")
def get_index_close_snapshots(
    date_: date | None = Query(default=None, alias="date"),
    db: Session = Depends(get_db),
) -> ApiResponse[list[OverseasIndexCloseSnapshotItem]]:
    """해외지수(나스닥종합) 장 마감 스냅샷 — 타임라인용. date 미지정 시 오늘(KST)."""
    day = date_ or _today_kst()
    return ApiResponse.ok(
        [_to_snapshot_item(s) for s in application.snapshots_on(db, day)]
    )


@router.get("/{exchange}/{symbol}")
def get_stock_detail(exchange: str, symbol: str) -> ApiResponse[OverseasStockDetailResponse]:
    """종목 상세 — 필터(거래대금순위·등락률·시총) 평가."""
    result = application.evaluate_stock(exchange.upper(), symbol.upper())
    stock: OverseasStockRank = result["stock"]
    swing = result["swing_high"]
    return ApiResponse.ok(
        OverseasStockDetailResponse(
            exchange=stock.exchange,
            symbol=stock.symbol,
            name=stock.name,
            ename=stock.ename,
            rank=stock.rank,
            price=stock.price,
            diff=stock.diff,
            rate=stock.rate,
            trading_value=stock.trading_value,
            market_cap=result["market_cap"],
            filter_results=[
                FilterResultItem(
                    filter_name=f.filter_name,
                    criteria_description=f.criteria_description,
                    actual_value=f.actual_value,
                    passed=f.passed,
                )
                for f in result["filters"]
            ],
            swing_high_signal=(
                OverseasSwingHighSignal(
                    peak_price=swing.peak_price,
                    peak_at=swing.peak_at,
                    gap_rate=swing.gap_rate,
                )
                if swing is not None
                else None
            ),
        )
    )


@router.get("/{exchange}/{symbol}/minute-candles")
def get_minute_candles(exchange: str, symbol: str) -> ApiResponse[list[OverseasMinuteCandleItem]]:
    candles = application.minute_candles(exchange.upper(), symbol.upper())
    return ApiResponse.ok(
        [
            OverseasMinuteCandleItem(
                time=c.date_time,
                open=c.open,
                high=c.high,
                low=c.low,
                close=c.close,
                volume=c.volume,
                trading_value=c.trading_value,
            )
            for c in candles
        ]
    )


@router.get("/{exchange}/{symbol}/daily-candles")
def get_daily_candles(exchange: str, symbol: str) -> ApiResponse[list[OverseasDailyCandleItem]]:
    candles = application.daily_candles(exchange.upper(), symbol.upper())
    return ApiResponse.ok(
        [
            OverseasDailyCandleItem(
                date=c.date.isoformat(),
                open=c.open,
                high=c.high,
                low=c.low,
                close=c.close,
                volume=c.volume,
            )
            for c in candles
        ]
    )


def _today_kst() -> date:
    from datetime import timedelta, timezone

    return datetime.now(timezone(timedelta(hours=KST_OFFSET_HOURS))).date()


def _to_rank_item(r: OverseasStockRank) -> OverseasStockRankItem:
    return OverseasStockRankItem(
        rank=r.rank,
        exchange=r.exchange,
        symbol=r.symbol,
        name=r.name,
        ename=r.ename,
        price=r.price,
        diff=r.diff,
        rate=r.rate,
        trading_value=r.trading_value,
    )


def _to_snapshot_item(s: IndexCloseSnapshot) -> OverseasIndexCloseSnapshotItem:
    return OverseasIndexCloseSnapshotItem(
        captured_at=s.captured_at,
        code=s.code,
        name=s.name,
        index_value=s.index_value,
        change_rate=s.change_rate,
    )
