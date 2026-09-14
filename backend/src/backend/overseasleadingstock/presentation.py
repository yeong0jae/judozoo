"""해외 주도주 API — 랭킹 / 종목 상세 / 분봉 / 일봉."""

from datetime import datetime

from fastapi import APIRouter, Query
from pydantic import BaseModel, Field

from backend.library.web import ApiResponse
from backend.overseasleadingstock import application
from backend.overseasleadingstock.domain import OverseasStockRank

router = APIRouter(prefix="/api/overseas-leading-stocks")

# 사용자가 -12~7 중 선택. 미지정 시 기본값.
MIN_CHANGE_RATE = -12
MAX_CHANGE_RATE = 7
DEFAULT_MIN_CHANGE_RATE = 7.0


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


@router.get("/{exchange}/{symbol}")
def get_stock_detail(exchange: str, symbol: str) -> ApiResponse[OverseasStockDetailResponse]:
    """종목 상세 — 필터(거래대금순위·등락률·시총) 평가."""
    result = application.evaluate_stock(exchange.upper(), symbol.upper())
    stock: OverseasStockRank = result["stock"]
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
