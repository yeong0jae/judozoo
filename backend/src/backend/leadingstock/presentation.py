"""주도주 API — 후보 / 돌파 레이더 / 시그널 로그 / 마감 스냅샷 / 지수·종목 캔들."""

from datetime import date, datetime

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.leadingstock import application, events
from backend.leadingstock.entities import step_eok
from backend.leadingstock.infrastructure import investor_snapshot_at
from backend.leadingstock.signals import MarketSignalType
from backend.library.db import get_db
from backend.library.time import now, today
from backend.auth.presentation import require_login
from backend.library.web import ApiResponse
from backend.settings import get_settings
from backend.stock.domain import Market

router = APIRouter(prefix="/api/leading-stocks")

MIN_CHANGE_RATE = -12
MAX_CHANGE_RATE = 7
MAX_THEME_CHIPS = 2


def _rate(min_change_rate: int | None) -> float:
    if min_change_rate is None:
        return get_settings().criteria.min_daily_price_change_rate
    return float(max(MIN_CHANGE_RATE, min(MAX_CHANGE_RATE, min_change_rate)))


class CandidateStockItem(BaseModel):
    rank: int
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    current_price: int = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    accumulated_trading_value: int = Field(serialization_alias="accumulatedTradingValue")
    themes: list[str]
    theme_count: int = Field(serialization_alias="themeCount")


class CandidateStocksResponse(BaseModel):
    queried_at: datetime = Field(serialization_alias="queriedAt")
    total_count: int = Field(serialization_alias="totalCount")
    stocks: list[CandidateStockItem]


class BreakoutRadarItem(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    current_price: int = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    day_high: int = Field(serialization_alias="dayHigh")
    peak_at: datetime = Field(serialization_alias="peakAt")
    gap_rate: float = Field(serialization_alias="gapRate")
    trading_value: int = Field(serialization_alias="tradingValue")
    themes: list[str]
    theme_count: int = Field(serialization_alias="themeCount")


class BreakoutRadarResponse(BaseModel):
    queried_at: datetime = Field(serialization_alias="queriedAt")
    total_count: int = Field(serialization_alias="totalCount")
    stocks: list[BreakoutRadarItem]


class SignalEventItem(BaseModel):
    occurred_at: datetime = Field(serialization_alias="occurredAt")
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    event_type: str = Field(serialization_alias="eventType")
    current_price: int = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    trading_value: int = Field(serialization_alias="tradingValue")
    gap_rate: float | None = Field(serialization_alias="gapRate")
    spike_ratio: float | None = Field(serialization_alias="spikeRatio")
    minute_trading_value: int | None = Field(serialization_alias="minuteTradingValue")
    spike_direction: str | None = Field(serialization_alias="spikeDirection")
    ma20: int | None
    theme: str | None


class SignalEventsResponse(BaseModel):
    date: date
    total_count: int = Field(serialization_alias="totalCount")
    events: list[SignalEventItem]


class MarketSignalEventItem(BaseModel):
    occurred_at: datetime = Field(serialization_alias="occurredAt")
    kind: str
    market: str
    side: str
    investor: str | None
    level: int | None
    threshold_eok: int | None = Field(serialization_alias="thresholdEok")
    net_amount_eok: int | None = Field(serialization_alias="netAmountEok")
    extreme_amount_eok: int | None = Field(serialization_alias="extremeAmountEok")
    index_value: float | None = Field(serialization_alias="indexValue")
    change_rate: float | None = Field(serialization_alias="changeRate")


class MarketSignalEventsResponse(BaseModel):
    date: date
    total_count: int = Field(serialization_alias="totalCount")
    events: list[MarketSignalEventItem]


class MarketCloseSnapshotItem(BaseModel):
    captured_at: datetime = Field(serialization_alias="capturedAt")
    market: str
    foreign_eok: int = Field(serialization_alias="foreignEok")
    institution_eok: int = Field(serialization_alias="institutionEok")
    individual_eok: int = Field(serialization_alias="individualEok")
    index_value: float | None = Field(serialization_alias="indexValue")
    change_rate: float | None = Field(serialization_alias="changeRate")


class MarketInvestorNetBuyItem(BaseModel):
    market: str
    foreign_eok: int = Field(serialization_alias="foreignEok")
    institution_eok: int = Field(serialization_alias="institutionEok")
    individual_eok: int = Field(serialization_alias="individualEok")
    other_corp_eok: int = Field(serialization_alias="otherCorpEok")
    financial_investment_eok: int = Field(serialization_alias="financialInvestmentEok")
    trust_eok: int = Field(serialization_alias="trustEok")
    pension_fund_eok: int = Field(serialization_alias="pensionFundEok")
    private_equity_eok: int = Field(serialization_alias="privateEquityEok")
    insurance_eok: int = Field(serialization_alias="insuranceEok")
    bank_eok: int = Field(serialization_alias="bankEok")
    index_value: float | None = Field(serialization_alias="indexValue")
    change_rate: float | None = Field(serialization_alias="changeRate")


class IndexMinuteCandleItem(BaseModel):
    time: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int


class MinuteCandleItem(BaseModel):
    time: datetime
    open: int
    high: int
    low: int
    close: int
    volume: int
    trading_value: int = Field(serialization_alias="tradingValue")


class DailyCandleChartItem(BaseModel):
    date: str
    open: int
    high: int
    low: int
    close: int
    volume: int


class FilterResultItem(BaseModel):
    filter_name: str = Field(serialization_alias="filterName")
    criteria_description: str = Field(serialization_alias="criteriaDescription")
    actual_value: str = Field(serialization_alias="actualValue")
    passed: bool


class SwingHighSignalItem(BaseModel):
    peak_price: int = Field(serialization_alias="peakPrice")
    peak_at: datetime = Field(serialization_alias="peakAt")
    gap_rate: float = Field(serialization_alias="gapRate")


class LeadingStockDetailResponse(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    current_price: int = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    relative_volume: float | None = Field(serialization_alias="relativeVolume")
    themes: list[str]
    swing_high_signal: SwingHighSignalItem | None = Field(serialization_alias="swingHighSignal")
    filter_results: list[FilterResultItem] = Field(serialization_alias="filterResults")


@router.get("/candidates")
def get_candidates(minChangeRate: int | None = Query(None)) -> ApiResponse[CandidateStocksResponse]:  # noqa: N803
    """Phase 1 후보 — 거래대금 순위 + 등락률 필터만 통과."""
    items = []
    for i, s in enumerate(application.find_candidate_stocks(_rate(minChangeRate))):
        themes = application.themes_of(s.stock_code)
        items.append(
            CandidateStockItem(
                rank=i + 1, stock_code=s.stock_code, stock_name=s.stock_name,
                current_price=s.current_price, price_change_rate=s.price_change_rate,
                accumulated_trading_value=s.accumulated_trading_value,
                themes=themes[:MAX_THEME_CHIPS], theme_count=len(themes),
            )
        )
    return ApiResponse.ok(
        CandidateStocksResponse(queried_at=now(), total_count=len(items), stocks=items)
    )


@router.get("/breakout-radar")
def get_breakout_radar(minChangeRate: int | None = Query(None)) -> ApiResponse[BreakoutRadarResponse]:  # noqa: N803
    """후보를 당일 고가 돌파에 가까운 순으로."""
    items = []
    for s in application.breakout_radar(_rate(minChangeRate)):
        themes = application.themes_of(s.stock_code)
        items.append(
            BreakoutRadarItem(
                stock_code=s.stock_code, stock_name=s.stock_name,
                current_price=s.current_price, price_change_rate=s.price_change_rate,
                day_high=s.day_high, peak_at=s.peak_at, gap_rate=s.gap_rate,
                trading_value=s.trading_value,
                themes=themes[:MAX_THEME_CHIPS], theme_count=len(themes),
            )
        )
    return ApiResponse.ok(
        BreakoutRadarResponse(queried_at=now(), total_count=len(items), stocks=items)
    )


@router.get("/signal-events")
def get_signal_events(
    date_: date | None = Query(None, alias="date"),
    db: Session = Depends(get_db),
    _user=Depends(require_login),
) -> ApiResponse[SignalEventsResponse]:
    """그날 발생한 돌파/임박/스파이크 전이를 최신순으로."""
    day = date_ or today()
    items = [
        SignalEventItem(
            occurred_at=e.occurred_at, stock_code=e.stock_code, stock_name=e.stock_name,
            event_type=e.event_type, current_price=e.current_price,
            price_change_rate=e.price_change_rate, trading_value=e.trading_value,
            gap_rate=e.gap_rate, spike_ratio=e.spike_ratio,
            minute_trading_value=e.minute_trading_value,
            spike_direction=e.spike_direction.value if e.spike_direction else None,
            ma20=e.ma20, theme=e.theme,
        )
        for e in events.signal_events_on(db, day)
    ]
    return ApiResponse.ok(SignalEventsResponse(date=day, total_count=len(items), events=items))


@router.get("/market-signal-events")
def get_market_signal_events(
    date_: date | None = Query(None, alias="date"),
    db: Session = Depends(get_db),
    _user=Depends(require_login),
) -> ApiResponse[MarketSignalEventsResponse]:
    """투자자 순매수 단계·흐름 전환 + 지수 반등·꺾임. 실시간 로그가 종목 시그널과 합쳐 보여준다."""
    day = date_ or today()
    items = [
        MarketSignalEventItem(
            occurred_at=e.occurred_at, kind=e.kind, market=e.market.name, side=e.side.value,
            investor=e.investor.value if e.investor else None, level=e.level,
            threshold_eok=e.level * step_eok(e.market) if e.level is not None else None,
            net_amount_eok=e.net_amount_eok, extreme_amount_eok=e.extreme_amount_eok,
            index_value=e.index_value, change_rate=e.change_rate,
        )
        for e in events.market_events_on(db, day)
    ]
    return ApiResponse.ok(MarketSignalEventsResponse(date=day, total_count=len(items), events=items))


@router.get("/market-close-snapshots")
def get_market_close_snapshots(
    date_: date | None = Query(None, alias="date"), db: Session = Depends(get_db)
) -> ApiResponse[list[MarketCloseSnapshotItem]]:
    """타임라인용 — 하루 시장당 한 건."""
    day = date_ or today()
    return ApiResponse.ok([
        MarketCloseSnapshotItem(
            captured_at=s.captured_at, market=s.market.name,
            foreign_eok=s.foreign_eok, institution_eok=s.institution_eok,
            individual_eok=s.individual_eok, index_value=s.index_value, change_rate=s.change_rate,
        )
        for s in events.close_snapshots_on(db, day)
    ])


@router.get("/market/investor-net-buy")
def get_market_investor_net_buy(
    at: datetime | None = Query(None), db: Session = Depends(get_db)
) -> ApiResponse[list[MarketInvestorNetBuyItem]]:
    """`at` 지정 시 그 시각 이하 가장 가까운 스냅샷(시그널 발생 시점 값), 미지정 시 당일 라이브 누적."""
    items = []
    if at is not None:
        for market in Market:
            s = investor_snapshot_at(db, market, at)
            if s is None:
                continue
            items.append(
                MarketInvestorNetBuyItem(
                    market=market.name, foreign_eok=s.foreign_eok,
                    institution_eok=s.institution_eok, individual_eok=s.individual_eok,
                    other_corp_eok=s.other_corp_eok,
                    financial_investment_eok=s.financial_investment_eok, trust_eok=s.trust_eok,
                    pension_fund_eok=s.pension_fund_eok, private_equity_eok=s.private_equity_eok,
                    insurance_eok=s.insurance_eok, bank_eok=s.bank_eok,
                    index_value=None, change_rate=None,
                )
            )
    else:
        for market, nb in events.investor_net_buy().items():
            items.append(
                MarketInvestorNetBuyItem(
                    market=market.name, foreign_eok=nb.foreign_eok,
                    institution_eok=nb.institution_eok, individual_eok=nb.individual_eok,
                    other_corp_eok=nb.other_corp_eok,
                    financial_investment_eok=nb.financial_investment_eok, trust_eok=nb.trust_eok,
                    pension_fund_eok=nb.pension_fund_eok, private_equity_eok=nb.private_equity_eok,
                    insurance_eok=nb.insurance_eok, bank_eok=nb.bank_eok,
                    index_value=nb.index_value, change_rate=nb.change_rate,
                )
            )
    return ApiResponse.ok(items)


@router.get("/index/{market}/minute-candles")
def get_index_minute_candles(
    market: str, date_: date | None = Query(None, alias="date"), db: Session = Depends(get_db)
) -> ApiResponse[list[IndexMinuteCandleItem]]:
    """실시간 로그에서 지수 행 선택 시 우측 차트용. 기준일 포함 직전 거래일까지 2거래일치."""
    day = date_ or today()
    target = Market[market.upper()]
    candles = events.index_candles_in_range(
        db, target, application.previous_trading_day(day), day
    )
    return ApiResponse.ok([
        IndexMinuteCandleItem(
            time=c.minute, open=c.open, high=c.high, low=c.low, close=c.close, volume=c.volume
        )
        for c in candles
    ])


@router.get("/candidates/{stock_code}/minute-candles")
def get_minute_candles(
    stock_code: str, date_: date | None = Query(None, alias="date")
) -> ApiResponse[list[MinuteCandleItem]]:
    """상세 캔들차트용 — 기준일 기준 최근 3거래일."""
    return ApiResponse.ok([
        MinuteCandleItem(
            time=c.date_time, open=c.open_price, high=c.high_price, low=c.low_price,
            close=c.close_price, volume=c.volume, trading_value=c.trading_value,
        )
        for c in application.minute_candles(stock_code, date_ or today())
    ])


@router.get("/candidates/{stock_code}/daily-candles")
def get_daily_candles(
    stock_code: str, date_: date | None = Query(None, alias="date")
) -> ApiResponse[list[DailyCandleChartItem]]:
    return ApiResponse.ok([
        DailyCandleChartItem(
            date=c.date.isoformat(), open=c.open_price, high=c.high_price,
            low=c.low_price, close=c.close_price, volume=c.volume,
        )
        for c in application.daily_candles(stock_code, date_ or today())
    ])


@router.get("/candidates/{stock_code}")
def get_stock_detail(stock_code: str) -> ApiResponse[LeadingStockDetailResponse]:
    """전체 필터(A~H) 평가 결과 + 상대거래량."""
    ev = application.evaluate_stock(stock_code)
    s = ev.stock
    return ApiResponse.ok(
        LeadingStockDetailResponse(
            stock_code=s.stock_code, stock_name=s.stock_name,
            current_price=s.current_price, price_change_rate=s.price_change_rate,
            relative_volume=ev.relative_volume,
            themes=application.themes_of(s.stock_code),
            swing_high_signal=(
                SwingHighSignalItem(
                    peak_price=ev.swing_high_signal.peak_price,
                    peak_at=ev.swing_high_signal.peak_at,
                    gap_rate=ev.swing_high_signal.gap_rate,
                )
                if ev.swing_high_signal
                else None
            ),
            filter_results=[
                FilterResultItem(
                    filter_name=r.filter_name, criteria_description=r.criteria_description,
                    actual_value=r.actual_value, passed=r.passed,
                )
                for r in ev.filter_results
            ],
        )
    )
