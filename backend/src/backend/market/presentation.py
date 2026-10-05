"""시황 API — 지수 / 캔들 / 수급 / 프로그램 / 선물 / 매크로 / 휴장.

**라우트 선언 순서가 중요하다.** FastAPI는 선언 순서로 매칭하므로 `calendar` · `macro` ·
`nasdaq` · `futures/night` 같은 리터럴 경로를 `{market}`보다 **먼저** 둬야 한다.
Spring은 리터럴 패턴을 우선해 순서가 상관없었다.
"""

from datetime import date

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from backend.library.db import get_db
from backend.library.time import today
from backend.library.web import ApiResponse
from backend.market import application, calendar
from backend.stock.domain import Market

router = APIRouter(prefix="/api/market")


# ── 응답 모델 ───────────────────────────────────────────────────────────


class CalendarStatus(BaseModel):
    is_holiday: bool = Field(serialization_alias="isHoliday")
    #: 오늘 전의 마지막 개장일 — 국내만(개장일 목록이 KIS 국내 것뿐이다). 모르면 None
    previous_open_day: date | None = Field(serialization_alias="previousOpenDay")


class IndexItem(BaseModel):
    current_value: float = Field(serialization_alias="currentValue")
    change_rate: float = Field(serialization_alias="changeRate")


class CandleItem(BaseModel):
    date: str  # yyyy-MM-dd (KST)
    time: str  # HH:mm:ss — 1d는 항상 00:00:00
    open: float
    high: float
    low: float
    close: float
    volume: float


class OrgBreakdownItem(BaseModel):
    financial_investment_eok: int = Field(serialization_alias="financialInvestmentEok")
    trust_eok: int = Field(serialization_alias="trustEok")
    pension_fund_eok: int = Field(serialization_alias="pensionFundEok")
    private_equity_eok: int = Field(serialization_alias="privateEquityEok")
    insurance_eok: int = Field(serialization_alias="insuranceEok")
    bank_eok: int = Field(serialization_alias="bankEok")
    other_finance_eok: int = Field(serialization_alias="otherFinanceEok")


class MarketInvestorDayItem(BaseModel):
    date: date
    individual_eok: int = Field(serialization_alias="individualEok")
    foreign_eok: int = Field(serialization_alias="foreignEok")
    institution_eok: int = Field(serialization_alias="institutionEok")
    other_corp_eok: int = Field(serialization_alias="otherCorpEok")
    breakdown: OrgBreakdownItem


class SessionOrgItem(BaseModel):
    financial_investment_eok: int = Field(serialization_alias="financialInvestmentEok")
    insurance_eok: int = Field(serialization_alias="insuranceEok")
    other_finance_eok: int = Field(serialization_alias="otherFinanceEok")
    trust_eok: int = Field(serialization_alias="trustEok")
    private_equity_eok: int = Field(serialization_alias="privateEquityEok")
    pension_fund_eok: int = Field(serialization_alias="pensionFundEok")
    bank_eok: int = Field(serialization_alias="bankEok")


class NetsItem(BaseModel):
    individual: int
    foreign: int
    institution: int
    other_corp: int = Field(serialization_alias="otherCorp")
    breakdown: SessionOrgItem


class SessionNetItem(BaseModel):
    name: str
    time: str
    nets: NetsItem | None
    #: 직전 스냅샷 대비 변화량. 마지막 스냅샷이 속한 구간에만, 그리고 오늘만 실린다.
    #: 화면은 이 값을 그대로 그리면 된다 — 예전처럼 브라우저가 직접 뺄 필요가 없다.
    delta: NetsItem | None = None


class SessionNetsResponse(BaseModel):
    """조회에 **실제로 쓴 날짜**를 함께 싣는다 — 공휴일이면 직전 거래일로 물러나므로,
    화면이 요청한 날짜로 라벨을 붙이면 거짓말이 된다."""

    date: date
    sessions: list[SessionNetItem]


class FuturesOrgItem(BaseModel):
    securities: int
    insurance: int
    merchant_bank: int = Field(serialization_alias="merchantBank")
    trust: int
    private_equity: int = Field(serialization_alias="privateEquity")
    fund: int
    bank: int
    other_org: int = Field(serialization_alias="otherOrg")


class FuturesNetsItem(BaseModel):
    foreign: int
    institution: int
    individual: int
    other_corp: int = Field(serialization_alias="otherCorp")
    breakdown: FuturesOrgItem


class FuturesSessionItem(BaseModel):
    name: str
    time: str
    nets: FuturesNetsItem | None
    #: 직전 스냅샷 대비 변화량. `SessionNetItem.delta`와 같은 규칙이다.
    delta: FuturesNetsItem | None = None


class FuturesSessionsResponse(BaseModel):
    """조회에 **실제로 쓴 날짜**를 함께 싣는다 — 공휴일이면 직전 거래일로 물러나므로,
    화면이 요청한 날짜로 라벨을 붙이면 거짓말이 된다."""

    date: date
    sessions: list[FuturesSessionItem]


class FuturesInvestorDayItem(BaseModel):
    date: date
    nets: FuturesNetsItem


class FuturesQuoteItem(BaseModel):
    futures_price: float = Field(serialization_alias="futuresPrice")
    change_rate: float = Field(serialization_alias="changeRate")
    spot: float
    basis: float
    dprt: float
    open_interest: int = Field(serialization_alias="openInterest")
    open_interest_change: int = Field(serialization_alias="openInterestChange")
    rmnn_days: int = Field(serialization_alias="rmnnDays")
    expiry_date: str = Field(serialization_alias="expiryDate")


class NightFuturesQuoteItem(BaseModel):
    price: float
    change_rate: float = Field(serialization_alias="changeRate")
    day_close: float = Field(serialization_alias="dayClose")
    gap: float
    open: float
    high: float
    low: float
    volume: int
    open_interest: int = Field(serialization_alias="openInterest")
    open_interest_change: int = Field(serialization_alias="openInterestChange")


class QuoteItem(BaseModel):
    price: float
    prev_close: float = Field(serialization_alias="prevClose")
    price_change: float = Field(serialization_alias="priceChange")
    change_rate: float = Field(serialization_alias="changeRate")


class MacroQuotesItem(BaseModel):
    usd_krw: QuoteItem | None = Field(serialization_alias="usdKrw")
    wti: QuoteItem | None
    vix: QuoteItem | None
    us10y: QuoteItem | None


class TodayNetInvestorsItem(BaseModel):
    individual: int
    foreign: int
    institution: int
    other_corp: int = Field(serialization_alias="otherCorp")


class TodayNetItem(BaseModel):
    """`futures`가 단위를 가른다 — 현물은 억원, 선물은 계약.

    `nets`는 수급만 못 받았을 때 null이다. 시세는 왔으므로 칸은 남는다.
    `trade_date`는 그 수급의 날짜다 — 장 열기 전·휴장일엔 직전 거래일이다."""

    market: str
    futures: bool
    index_value: float = Field(serialization_alias="indexValue")
    change_rate: float = Field(serialization_alias="changeRate")
    nets: TodayNetInvestorsItem | None
    trade_date: date | None = Field(serialization_alias="tradeDate")


# ── 변환기 ─────────────────────────────────────────────────────────────


def _bar_items(bars) -> list[CandleItem]:
    """야후·KIS 선물 봉은 이미 date/time 문자열을 들고 있다."""
    return [
        CandleItem(date=b.date, time=b.time, open=b.open, high=b.high, low=b.low, close=b.close, volume=b.volume)
        for b in bars
    ]


def _toss_candle_items(candles) -> list[CandleItem]:
    return [
        CandleItem(
            date=c.timestamp.date().isoformat(),
            time=c.timestamp.time().isoformat(),
            open=c.open, high=c.high, low=c.low, close=c.close, volume=c.volume,
        )
        for c in candles
    ]


def _nets_item(n) -> NetsItem | None:
    if n is None:
        return None
    return NetsItem(
        individual=n.individual, foreign=n.foreign, institution=n.institution,
        other_corp=n.other_corp, breakdown=SessionOrgItem(**vars(n.breakdown)),
    )


def _futures_nets_item(n) -> FuturesNetsItem | None:
    if n is None:
        return None
    return FuturesNetsItem(
        foreign=n.foreign, institution=n.institution, individual=n.individual,
        other_corp=n.other_corp, breakdown=FuturesOrgItem(**vars(n.breakdown)),
    )


def _quote_item(q) -> QuoteItem | None:
    return None if q is None else QuoteItem(**vars(q))


# ── 리터럴 경로 (반드시 {market}보다 먼저) ──────────────────────────────


@router.get("/calendar/status")
def calendar_status(region: calendar.Region = Query()) -> ApiResponse[CalendarStatus]:
    """시장 휴장 상태 — 배너용. region KR(국내)/US(해외)."""
    previous = calendar.previous_open_day(region.today()) if region is calendar.Region.KR else None
    return ApiResponse.ok(CalendarStatus(is_holiday=calendar.is_holiday(region), previous_open_day=previous))


@router.get("/kospi")
def kospi() -> ApiResponse[IndexItem]:
    r = application.get_kospi()
    return ApiResponse.ok(IndexItem(current_value=r.current_value, change_rate=r.change_rate))


@router.get("/kosdaq")
def kosdaq() -> ApiResponse[IndexItem]:
    r = application.get_kosdaq()
    return ApiResponse.ok(IndexItem(current_value=r.current_value, change_rate=r.change_rate))


@router.get("/investor/today")
def investor_today(db: Session = Depends(get_db)) -> ApiResponse[list[TodayNetItem]]:
    """첫 화면 "오늘의 수급" — 코스피·코스닥 현물과 두 지수선물의 당일 누적 순매수."""
    return ApiResponse.ok(
        [
            TodayNetItem(
                market=n.market.name, futures=n.futures,
                index_value=n.index_value, change_rate=n.change_rate,
                nets=None if n.nets is None else TodayNetInvestorsItem(**vars(n.nets)),
                trade_date=n.trade_date,
            )
            for n in application.today_nets(db)
        ]
    )


@router.get("/macro/quotes")
def macro_quotes() -> ApiResponse[MacroQuotesItem]:
    q = application.macro_quotes()
    return ApiResponse.ok(
        MacroQuotesItem(
            usd_krw=_quote_item(q.usd_krw),
            wti=_quote_item(q.wti),
            vix=_quote_item(q.vix),
            us10y=_quote_item(q.us10y),
        )
    )


@router.get("/macro/candles")
def macro_candles(target: str = Query(), interval: str = Query()) -> ApiResponse[list[CandleItem]]:
    return ApiResponse.ok(_bar_items(application.macro_candles(target, interval)))


@router.get("/nasdaq/quote")
def nasdaq_quote() -> ApiResponse[QuoteItem | None]:
    return ApiResponse.ok(_quote_item(application.nasdaq_quote()))


@router.get("/nasdaq/candles")
def nasdaq_candles(interval: str = Query()) -> ApiResponse[list[CandleItem]]:
    return ApiResponse.ok(_bar_items(application.nasdaq_candles(interval)))


@router.get("/futures/night/quote")
def night_quote() -> ApiResponse[NightFuturesQuoteItem | None]:
    q = application.night_futures_quote()
    return ApiResponse.ok(None if q is None else NightFuturesQuoteItem(**vars(q)))


@router.get("/futures/night/candles")
def night_candles(interval: str = Query(), count: int = Query(90)) -> ApiResponse[list[CandleItem]]:
    if interval == "1d":
        bars = application.night_futures_daily_candles(count)
    elif interval == "1m":
        bars = application.night_futures_minute_candles()
    else:
        bars = []
    return ApiResponse.ok(_bar_items(bars))


@router.get("/futures/nasdaq/quote")
def nasdaq_futures_quote() -> ApiResponse[QuoteItem | None]:
    return ApiResponse.ok(_quote_item(application.nasdaq_futures_quote()))


@router.get("/futures/nasdaq/candles")
def nasdaq_futures_candles(interval: str = Query()) -> ApiResponse[list[CandleItem]]:
    return ApiResponse.ok(_bar_items(application.nasdaq_futures_candles(interval)))


# ── 선물 {market} ───────────────────────────────────────────────────────


@router.get("/futures/{market}/quote")
def futures_quote(market: Market) -> ApiResponse[FuturesQuoteItem | None]:
    q = application.futures_quote(market)
    if q is None:
        return ApiResponse.ok(None)
    return ApiResponse.ok(FuturesQuoteItem(**vars(q)))


@router.get("/futures/{market}/investor/daily")
def futures_investor_daily(
    market: Market, count: int = Query(10), db: Session = Depends(get_db)
) -> ApiResponse[list[FuturesInvestorDayItem]]:
    return ApiResponse.ok(
        [
            FuturesInvestorDayItem(date=d.date, nets=_futures_nets_item(d.nets))
            for d in application.futures_investor_daily_history(db, market, count)
        ]
    )


@router.get("/futures/{market}/investor/sessions")
def futures_investor_sessions(
    market: Market, date_: date | None = Query(None, alias="date"), db: Session = Depends(get_db)
) -> ApiResponse[FuturesSessionsResponse]:
    used, sessions = application.futures_investor_sessions(db, market, date_ or today())
    return ApiResponse.ok(
        FuturesSessionsResponse(
            date=used,
            sessions=[
                FuturesSessionItem(
                    name=s.name, time=s.time,
                    nets=_futures_nets_item(s.nets), delta=_futures_nets_item(s.delta),
                )
                for s in sessions
            ],
        )
    )


@router.get("/futures/{market}/candles")
def futures_candles(
    market: Market, interval: str = Query(), count: int = Query(90)
) -> ApiResponse[list[CandleItem]]:
    if interval == "1d":
        bars = application.futures_daily_candles(market, count)
    elif interval == "1m":
        bars = application.futures_minute_candles(market)
    else:
        bars = []
    return ApiResponse.ok(_bar_items(bars))


# ── 시장 {market} ───────────────────────────────────────────────────────


@router.get("/{market}/candles")
def market_candles(
    market: Market, interval: str = Query(), count: int = Query(90)
) -> ApiResponse[list[CandleItem]]:
    if interval == "1d":
        candles = application.daily_candles(market, count)
    elif interval == "1m":
        candles = application.minute_candles_today(market)
    else:
        candles = []
    return ApiResponse.ok(_toss_candle_items(candles))


@router.get("/{market}/investor/daily")
def investor_daily(
    market: Market, count: int = Query(10), db: Session = Depends(get_db)
) -> ApiResponse[list[MarketInvestorDayItem]]:
    return ApiResponse.ok(
        [
            MarketInvestorDayItem(
                date=d.date,
                individual_eok=d.individual_eok,
                foreign_eok=d.foreign_eok,
                institution_eok=d.institution_eok,
                other_corp_eok=d.other_corp_eok,
                breakdown=OrgBreakdownItem(**vars(d.breakdown)),
            )
            for d in application.investor_daily_history(db, market, count)
        ]
    )


@router.get("/{market}/investor/sessions")
def investor_sessions(
    market: Market, date_: date | None = Query(None, alias="date"), db: Session = Depends(get_db)
) -> ApiResponse[SessionNetsResponse]:
    used, sessions = application.investor_sessions(db, market, date_ or today())
    return ApiResponse.ok(
        SessionNetsResponse(
            date=used,
            sessions=[
                SessionNetItem(
                    name=s.name, time=s.time,
                    nets=_nets_item(s.nets), delta=_nets_item(s.delta),
                )
                for s in sessions
            ],
        )
    )
