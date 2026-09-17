"""주도주 API — 후보 / 돌파 레이더 / 시그널 로그 / 지수·종목 캔들."""

from datetime import date, datetime

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.auth.presentation import current_user
from backend.leadingstock import application, events
from backend.leadingstock.entities import step_eok
from backend.leadingstock.infrastructure import investor_snapshot_at
from backend.leadingstock.signals import MarketSignalType
from backend.library.db import get_db
from backend.library.time import now, today
from backend.library.web import ApiResponse
from backend.settings import get_settings
from backend.stock.domain import Market

router = APIRouter(prefix="/api/leading-stocks")

MIN_CHANGE_RATE = -12
MAX_CHANGE_RATE = 7


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


class CandidateStocksResponse(BaseModel):
    queried_at: datetime = Field(serialization_alias="queriedAt")
    total_count: int = Field(serialization_alias="totalCount")
    stocks: list[CandidateStockItem]


class BreakoutRadarItem(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    current_price: int = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    peak_price: int = Field(serialization_alias="peakPrice")
    peak_at: datetime = Field(serialization_alias="peakAt")
    gap_rate: float = Field(serialization_alias="gapRate")
    trough_price: int | None = Field(default=None, serialization_alias="troughPrice")
    trough_at: datetime | None = Field(default=None, serialization_alias="troughAt")
    support_gap_rate: float | None = Field(default=None, serialization_alias="supportGapRate")
    trading_value: int = Field(serialization_alias="tradingValue")


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
    ma: int | None


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


class LeadingStockDetailResponse(BaseModel):
    stock_code: str = Field(serialization_alias="stockCode")
    stock_name: str = Field(serialization_alias="stockName")
    current_price: int = Field(serialization_alias="currentPrice")
    price_change_rate: float = Field(serialization_alias="priceChangeRate")
    relative_volume: float | None = Field(serialization_alias="relativeVolume")
    #: 소속 시장 — 카탈로그에 없으면 None(화면은 그때 아무것도 적지 않는다)
    market: str | None = None
    filter_results: list[FilterResultItem] = Field(serialization_alias="filterResults")


@router.get("/candidates")
def get_candidates(minChangeRate: int | None = Query(None)) -> ApiResponse[CandidateStocksResponse]:  # noqa: N803
    """Phase 1 후보 — 거래대금 순위 + 등락률 필터만 통과."""
    items = [
        CandidateStockItem(
            rank=i + 1, stock_code=s.stock_code, stock_name=s.stock_name,
            current_price=s.current_price, price_change_rate=s.price_change_rate,
            accumulated_trading_value=s.accumulated_trading_value,
        )
        for i, s in enumerate(application.find_candidate_stocks(_rate(minChangeRate)))
    ]
    return ApiResponse.ok(
        CandidateStocksResponse(queried_at=now(), total_count=len(items), stocks=items)
    )


#: 첫 화면 주도주 칸 수. 늘리려면 여기만 고친다.
LEADERS_COUNT = 5


@router.get("/leaders")
def get_leaders() -> ApiResponse[list[CandidateStockItem]]:
    """첫 화면용 — 거래대금·등락률이 함께 높은 상위 `LEADERS_COUNT`개.

    `/candidates`와 달리 **등락률 파라미터를 받지 않는다.** 첫 화면은 보는 사람이
    후보 목록에 걸어둔 기준과 무관하게 같은 답을 보여야 한다.
    """
    return ApiResponse.ok([
        CandidateStockItem(
            rank=i + 1, stock_code=s.stock_code, stock_name=s.stock_name,
            current_price=s.current_price, price_change_rate=s.price_change_rate,
            accumulated_trading_value=s.accumulated_trading_value,
        )
        for i, s in enumerate(application.find_leaders(LEADERS_COUNT))
    ])


@router.get("/breakout-radar")
def get_breakout_radar(
    request: Request,
    mode: str = Query("resistance"),
) -> ApiResponse[BreakoutRadarResponse]:
    """후보를 돌파선(최근 3거래일 고가) 근접 순으로. 눌림선(최근 3거래일 저가)도 함께 싣는다.

    **등락률 파라미터를 받지 않는다** — 눌림은 내린 종목에서 나오는 신호라 상승률 하한을
    걸면 그쪽이 빈다. 어느 쪽에 얼마나 가까운 것만 볼지는 화면이 정한다.

    `mode`가 **어느 선을 기준으로 볼지**를 정한다. 그 선에서 `RADAR_NEAR_RATE`% 이내인
    종목만 담고, 가까운 순으로 세운다 — 멀리 있는 종목은 지금 할 일이 없다.

    **미로그인이면 상위 `RADAR_PREVIEW_COUNT`개만 내려간다.** `total_count`는 근접 범위로
    거른 뒤, 건수로 자르기 전의 수다. 받는 쪽이 둘을 비교해 잘렸는지 알고, 두 숫자가
    같은 모수를 세므로 "N개 중 M개"가 거짓말이 되지 않는다.
    """
    near = [
        BreakoutRadarItem(
            stock_code=s.stock_code, stock_name=s.stock_name,
            current_price=s.current_price, price_change_rate=s.price_change_rate,
            peak_price=s.peak_price, peak_at=s.peak_at, gap_rate=s.gap_rate,
            trough_price=s.trough_price, trough_at=s.trough_at, support_gap_rate=s.support_gap_rate,
            trading_value=s.trading_value,
        )
        for s in application.breakout_radar()
        if (
            s.support_gap_rate is not None and s.support_gap_rate <= RADAR_NEAR_RATE
            if mode == "support"
            else s.gap_rate <= RADAR_NEAR_RATE
        )
    ]
    near.sort(
        key=lambda i: i.support_gap_rate if mode == "support" and i.support_gap_rate is not None
        else i.gap_rate
    )
    items = near
    total = len(items)
    if current_user(request) is None:
        items = items[:RADAR_PREVIEW_COUNT]
    return ApiResponse.ok(
        BreakoutRadarResponse(queried_at=now(), total_count=total, stocks=items)
    )


#: 미로그인 미리보기로 내려보내는 최신 시그널 건수.
PREVIEW_COUNT = 10

#: 미로그인 미리보기 등락률 하한(%). 감시 풀이 -12%까지 넓어(`signal_event.min_change_rate`)
#: 그냥 자르면 미리보기 열 칸이 급락주 전이로 찰 수 있다 — 자르기 전에 건다.
#: 0이면 "내린 종목만 뺀다"는 뜻이고, 로그인 화면의 등락률 필터 기본값과도 같다.
PREVIEW_MIN_CHANGE_RATE = 0.0

#: 미로그인 미리보기로 내려보내는 눌림·돌파 종목 수. 시그널은 그날 쌓인 로그라 10건이지만
#: 여기는 "지금 가장 가까운 것"이 핵심이라 상위 몇 개면 맛이 보인다.
RADAR_PREVIEW_COUNT = 5

#: 눌림·돌파로 볼 근접 범위(%). 선에서 이만큼 안에 든 종목만 목록에 담는다.
#: 화면이 색을 넣는 기준(`BreakoutRadarPage`의 `NEAR`)과 **같은 값이어야 한다** —
#: 다르면 목록에 있는데 색이 없는 줄이나 그 반대가 생긴다.
RADAR_NEAR_RATE = 3.0


@router.get("/signal-events")
def get_signal_events(
    request: Request,
    date_: date | None = Query(None, alias="date"),
    db: Session = Depends(get_db),
) -> ApiResponse[SignalEventsResponse]:
    """그날 발생한 돌파/임박/스파이크 전이를 최신순으로.

    **미로그인이면 등락률 `PREVIEW_MIN_CHANGE_RATE`% 이상 중 최신 `PREVIEW_COUNT`건만
    내려간다.** 화면에서 자르면 나머지가 이미 브라우저에 도착해 있어 개발자도구로 읽힌다 —
    잘라내는 일은 서버가 해야 한다. `total_count`는 등락률로 거른 뒤, 건수로 자르기 전의
    수라 받는 쪽이 둘을 비교해 잘렸는지 안다.
    """
    day = date_ or today()
    rows = events.signal_events_on(db, day)
    guest = current_user(request) is None
    if guest:
        rows = [e for e in rows if e.price_change_rate >= PREVIEW_MIN_CHANGE_RATE]
    items = [
        SignalEventItem(
            occurred_at=e.occurred_at, stock_code=e.stock_code, stock_name=e.stock_name,
            event_type=e.event_type, current_price=e.current_price,
            price_change_rate=e.price_change_rate, trading_value=e.trading_value,
            gap_rate=e.gap_rate, spike_ratio=e.spike_ratio,
            minute_trading_value=e.minute_trading_value,
            spike_direction=e.spike_direction.value if e.spike_direction else None,
            ma=e.ma,
        )
        for e in rows
    ]
    total = len(items)
    if guest:
        items = items[:PREVIEW_COUNT]
    return ApiResponse.ok(SignalEventsResponse(date=day, total_count=total, events=items))


@router.get("/market-signal-events")
def get_market_signal_events(
    date_: date | None = Query(None, alias="date"),
    db: Session = Depends(get_db),
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
    """전체 필터 평가 결과 + 상대거래량. 순서는 판별력이 큰 것부터."""
    ev = application.evaluate_stock(stock_code)
    s = ev.stock
    return ApiResponse.ok(
        LeadingStockDetailResponse(
            stock_code=s.stock_code, stock_name=s.stock_name,
            current_price=s.current_price, price_change_rate=s.price_change_rate,
            relative_volume=ev.relative_volume,
            market=ev.market.name if ev.market else None,
            filter_results=[
                FilterResultItem(
                    filter_name=r.filter_name, criteria_description=r.criteria_description,
                    actual_value=r.actual_value, passed=r.passed,
                )
                for r in ev.filter_results
            ],
        )
    )
