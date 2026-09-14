"""시황 — 지수 / 캔들 / 투자자 수급 / 프로그램매매 / 선물 / 매크로.

세션(오전·오후·마감) 순매수는 값을 직접 주는 API가 없다. 폴러가 찍어둔 **당일 누적 스냅샷의
경계 diff**로 만든다 — 이 계산이 이 모듈의 핵심이고, 경계 시각이 어긋나면 값이 통째로 틀린다.
"""

import logging
import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.leadingstock.infrastructure import MarketInvestorSnapshot, investor_snapshot_at
from backend.library.cache import is_empty, ttl_cache
from backend.library.time import now, today
from backend.market import calendar
from backend.market.domain import (
    FuturesInvestorSnapshot,
    FuturesNets,
    Nets,
    SessionOrg,
)
from backend.platform.kis import futures as kis_futures
from backend.platform.kiwoom import index as kiwoom_index
from backend.platform.kiwoom import sector_investor as kiwoom_sector
from backend.platform.toss import market_indicator as toss_indicator
from backend.platform.yahoo import client as yahoo
from backend.stock.domain import Market

log = logging.getLogger(__name__)

# 세션 경계 — 통합(KRX+NXT) 기준이라 프리(08:00~09:00)·애프터(15:40~20:00)도 누적에 들어간다.
_OPEN = time(9, 0)
_MORNING_END = time(12, 0)
_AFTERNOON_END = time(15, 0)
_CLOSE = time(15, 40)
_AFTER_END = time(20, 0)

_MRKT_TP = {Market.KOSPI: "0", Market.KOSDAQ: "1"}
_INDEX_CODE = {Market.KOSPI: ("001", "0"), Market.KOSDAQ: ("101", "1")}


# ── 지수 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class IndexResult:
    current_value: float
    change_rate: float  # 단위 % (예: +0.42)


@ttl_cache("kospiIndex", ttl_seconds=5, maxsize=1)
def get_kospi() -> IndexResult:
    return _index_of(Market.KOSPI)


@ttl_cache("kosdaqIndex", ttl_seconds=5, maxsize=1)
def get_kosdaq() -> IndexResult:
    return _index_of(Market.KOSDAQ)


def _index_of(market: Market) -> IndexResult:
    code, mrkt_tp = _INDEX_CODE[market]
    snap = kiwoom_index.fetch_index(code, mrkt_tp)
    if snap is None:
        log.warning("%s 지수 조회 실패 — 0으로 폴백", market.name)
        return IndexResult(current_value=0.0, change_rate=0.0)
    return IndexResult(current_value=snap.current_value, change_rate=snap.change_rate)


# ── 지수 캔들 (토스) ────────────────────────────────────────────────────

_MAX_PAGE_SIZE = 200
_MAX_PAGES = 6  # 200×6=1200봉 — 2일치 정규장(780분)을 여유 있게 덮는다


def daily_candles(market: Market, count: int) -> list[toss_indicator.TossCandle]:
    """최근 `count`봉 일봉(오름차순)."""
    page = toss_indicator.fetch_candles(market.name, "1d", max(1, min(count, _MAX_PAGE_SIZE)))
    return sorted(page.candles, key=lambda c: c.timestamp)


def minute_candles_today(market: Market) -> list[toss_indicator.TossCandle]:
    """가장 최근 2영업일의 1분봉(오름차순) — 여러 페이지를 모아 2일치를 덮는다."""
    collected: list[toss_indicator.TossCandle] = []
    seen_dates: set[date] = set()
    before: str | None = None

    for _ in range(_MAX_PAGES):
        page = toss_indicator.fetch_candles(market.name, "1m", _MAX_PAGE_SIZE, before)
        if not page.candles:
            break
        collected.extend(page.candles)
        seen_dates.update(c.timestamp.date() for c in page.candles)
        # 고유 거래일이 3개 이상 잡히면 2일치 수집이 끝난 것
        if len(seen_dates) > 2 or page.next_before is None:
            break
        before = page.next_before

    valid = set(sorted(seen_dates, reverse=True)[:2])
    return sorted((c for c in collected if c.timestamp.date() in valid), key=lambda c: c.timestamp)


# ── 시장 투자자 수급 ────────────────────────────────────────────────────


@dataclass(frozen=True)
class OrgBreakdown:
    """기관 세부 순매수(억원) — 키움 7종."""

    financial_investment_eok: int
    trust_eok: int
    pension_fund_eok: int
    private_equity_eok: int
    insurance_eok: int
    bank_eok: int
    other_finance_eok: int


@dataclass(frozen=True)
class MarketInvestorDay:
    date: date
    individual_eok: int
    foreign_eok: int
    institution_eok: int
    other_corp_eok: int
    breakdown: OrgBreakdown


@dataclass(frozen=True)
class SessionNet:
    name: str
    time: str
    nets: Nets | None


def investor_daily_history(market: Market, count: int) -> list[MarketInvestorDay]:
    """최근 `count` 거래일 일별 순매수. 최신순.

    주말·공휴일은 건너뛴다 — 휴장일에 base_dt로 부르면 **직전 영업일 값이 그대로 와 중복된다**.
    """
    mrkt_tp = _MRKT_TP[market]
    out: list[MarketInvestorDay] = []
    day = today()
    guard = 0
    while len(out) < count and guard < count * 3 + 10:
        guard += 1
        if day.weekday() >= 5 or calendar.is_open(day) is False:
            day -= timedelta(days=1)
            continue
        nb = kiwoom_sector.fetch_sector_net_buy(mrkt_tp, day.strftime("%Y%m%d"))
        if nb is not None:
            out.append(
                MarketInvestorDay(
                    date=day,
                    individual_eok=nb.individual_eok,
                    foreign_eok=nb.foreign_eok,
                    institution_eok=nb.institution_eok,
                    other_corp_eok=nb.other_corp_eok,
                    breakdown=OrgBreakdown(
                        financial_investment_eok=nb.financial_investment_eok,
                        trust_eok=nb.trust_eok,
                        pension_fund_eok=nb.pension_fund_eok,
                        private_equity_eok=nb.private_equity_eok,
                        insurance_eok=nb.insurance_eok,
                        bank_eok=nb.bank_eok,
                        other_finance_eok=nb.other_finance_eok,
                    ),
                )
            )
        day -= timedelta(days=1)
    return out


def _latest_date_with_data(session: Session, model, market: Market, on: date) -> date:
    """`on` 이하에서 **데이터가 실제로 있는** 가장 최근 거래일.

    주말은 프론트가 직전 평일로 물러나 보내지만 공휴일은 못 잡는다. 여기서 받아
    빈 화면 대신 직전 거래일을 보여준다. 아예 없으면 요청일을 그대로 돌려준다 —
    호출자가 "그날은 비었다"로 처리하게 둔다.
    """
    found = session.scalar(
        select(model.trade_date)
        .where(model.market == market, model.trade_date <= on)
        .order_by(model.trade_date.desc())
        .limit(1)
    )
    return found or on


def investor_sessions(session: Session, market: Market, on: date) -> tuple[date, list[SessionNet]]:
    """세션별 순매수 — 당일 누적 스냅샷 경계 diff. 데이터 없는 세션은 nets=None.

    다섯 구간의 합 = 그날 최종 누적(= 일별 표의 그날 값). 폴러가 프리 스냅샷을 남기기 전 과거는
    프리 경계가 없어, 프리를 "집계 전"으로 두고 오전에 프리를 포함해 **합을 보존**한다.

    **실제로 조회한 날짜를 함께 돌려준다** — 공휴일이면 직전 거래일로 물러나므로,
    화면이 요청한 날짜로 라벨을 붙이면 거짓말이 된다.
    """
    on = _latest_date_with_data(session, MarketInvestorSnapshot, market, on)

    def at(t: time):
        return investor_snapshot_at(session, market, datetime.combine(on, t))

    open_, morning = at(_OPEN), at(_MORNING_END)
    afternoon, close, after_close = at(_AFTERNOON_END), at(_CLOSE), at(_AFTER_END)

    def diff(later, earlier) -> Nets | None:
        if later is None or earlier is None:
            return None
        return later.nets() - earlier.nets()

    morning_net = diff(morning, open_) if open_ is not None else (morning.nets() if morning else None)
    return on, [
        SessionNet("프리마켓", "08:00~09:00", open_.nets() if open_ else None),
        SessionNet("오전", "09:00~12:00", morning_net),
        SessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
        SessionNet("마감 구간", "15:00~15:40", diff(close, afternoon)),
        SessionNet("애프터마켓", "15:40~20:00", diff(after_close, close)),
    ]


# ── 선물 투자자 수급 ────────────────────────────────────────────────────

_FUT_MORNING_END = time(12, 0)
_FUT_AFTERNOON_END = time(15, 0)
_FUT_CLOSE = time(15, 45)
_HOLIDAY_MARGIN = 5  # 휴장일 스냅샷을 걸러내도 count를 채우도록 더 읽는 여유분


@dataclass(frozen=True)
class FuturesSessionNet:
    name: str
    time: str
    nets: FuturesNets | None


@dataclass(frozen=True)
class FuturesInvestorDay:
    date: date
    nets: FuturesNets


def record_futures_investors(
    session: Session, market: Market, trade_date: date, captured_at: datetime, investors
) -> None:
    at = now()
    session.add(
        FuturesInvestorSnapshot(
            market=market,
            trade_date=trade_date,
            captured_at=captured_at,
            foreign_qty=investors.foreign,
            institution_qty=investors.institution,
            individual_qty=investors.individual,
            securities_qty=investors.securities,
            insurance_qty=investors.insurance,
            merchant_bank_qty=investors.merchant_bank,
            trust_qty=investors.trust,
            private_equity_qty=investors.private_equity,
            fund_qty=investors.fund,
            bank_qty=investors.bank,
            other_org_qty=investors.other_org,
            other_corp_qty=investors.other_corp,
            created_at=at,
            updated_at=at,
        )
    )
    session.commit()


def futures_investor_daily_history(
    session: Session, market: Market, count: int
) -> list[FuturesInvestorDay]:
    """최근 `count` 거래일 일별 순매수(계약). 각 거래일의 **마지막 스냅샷** = 그날 누적. 최신순.

    폴러가 적재한 날만 나온다(과거 소급 불가). 휴장일에 잘못 적재된 스냅샷은 제외한다.
    """
    rows = session.scalars(
        select(FuturesInvestorSnapshot)
        .where(FuturesInvestorSnapshot.market == market)
        .order_by(
            FuturesInvestorSnapshot.trade_date.desc(),
            FuturesInvestorSnapshot.captured_at.desc(),
        )
    )
    out: list[FuturesInvestorDay] = []
    seen: set[date] = set()
    for row in rows:
        if row.trade_date in seen:
            continue
        seen.add(row.trade_date)
        if calendar.is_open(row.trade_date) is False:
            continue
        out.append(FuturesInvestorDay(row.trade_date, row.nets()))
        if len(out) == count:
            break
        if len(seen) > count + _HOLIDAY_MARGIN:
            break
    return out


def futures_investor_sessions(
    session: Session, market: Market, on: date
) -> tuple[date, list[FuturesSessionNet]]:
    """세션별 순매수(계약) — 스냅샷이 없는 세션은 nets=None."""
    on = _latest_date_with_data(session, FuturesInvestorSnapshot, market, on)

    def at(t: time):
        return _futures_snapshot_at(session, market, datetime.combine(on, t))

    morning, afternoon, close = at(_FUT_MORNING_END), at(_FUT_AFTERNOON_END), at(_FUT_CLOSE)

    def diff(later, earlier) -> FuturesNets | None:
        if later is None or earlier is None:
            return None
        return later.nets() - earlier.nets()

    return on, [
        FuturesSessionNet("오전", "08:45~12:00", morning.nets() if morning else None),
        FuturesSessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
        FuturesSessionNet("마감 구간", "15:00~15:45", diff(close, afternoon)),
    ]


def _futures_snapshot_at(session: Session, market: Market, at: datetime):
    return session.scalar(
        select(FuturesInvestorSnapshot)
        .where(
            FuturesInvestorSnapshot.market == market,
            FuturesInvestorSnapshot.trade_date == at.date(),
            FuturesInvestorSnapshot.captured_at <= at,
        )
        .order_by(FuturesInvestorSnapshot.captured_at.desc())
        .limit(1)
    )


# ── 지수선물 시세 ───────────────────────────────────────────────────────

_MINUTE_DAYS = 2                  # 분봉 수집 거래일 수
_MINUTE_PAGES = 7                 # 하루(08:45~15:45=420분)를 102봉씩 덮는 최대 페이지 수
_FUT_SESSION_START = time(8, 45)  # 선물 개장(동시호가 08:30~08:45)
_FUT_SESSION_END = time(15, 45)   # 조회 상한(마감 동시호가 체결까지 포함)


@dataclass(frozen=True)
class FuturesInvestorsSummary:
    foreign: int
    individual: int
    institution: int


@dataclass(frozen=True)
class FuturesQuote:
    """지수선물 시세 요약 — 값은 지수 포인트."""

    futures_price: float
    change_rate: float
    spot: float
    basis: float                 # 선물 − 현물
    dprt: float                  # 괴리율(%)
    open_interest: int
    open_interest_change: int
    rmnn_days: int
    expiry_date: str             # 만기일 yyyy-MM-dd
    investors: FuturesInvestorsSummary | None


@ttl_cache("futuresQuote", ttl_seconds=5, maxsize=2, skip_if=lambda r: r is None)
def futures_quote(market: Market) -> FuturesQuote | None:
    near = kis_futures.fetch_near_month(market)
    if near is None:
        return None
    current = today()
    daily = kis_futures.fetch_daily(near.iscd, current - timedelta(days=10), current)
    if daily is None:
        return None
    s = daily.summary
    investors = kis_futures.fetch_investors(market)
    return FuturesQuote(
        futures_price=s.futures_price,
        change_rate=s.change_rate,
        spot=s.spot,
        basis=s.basis,
        dprt=s.dprt,
        open_interest=s.open_interest,
        open_interest_change=s.open_interest_change,
        rmnn_days=near.rmnn_days,
        expiry_date=_expiry_of(near.name) or "",
        investors=(
            FuturesInvestorsSummary(investors.foreign, investors.individual, investors.institution)
            if investors
            else None
        ),
    )


def _expiry_of(name: str) -> str | None:
    """근월물 이름("F 202609")의 만기월 **둘째 목요일** = KRX 파생 최종거래일."""
    match = re.search(r"(\d{6})", name or "")
    if match is None:
        return None
    ym = match.group(1)
    try:
        first = date(int(ym[:4]), int(ym[4:6]), 1)
    except ValueError:
        return None
    # weekday(): 월=0 … 목=3
    first_thu = first + timedelta(days=(3 - first.weekday()) % 7)
    return (first_thu + timedelta(weeks=1)).isoformat()


@ttl_cache(
    "futuresCandles",
    ttl_seconds=60,
    maxsize=8,
    key=lambda market, interval, count: f"{market.name}:{interval}:{count}",
    skip_if=is_empty,
)
def futures_candles(market: Market, interval: str, count: int) -> list[kis_futures.FuturesBar]:
    near = kis_futures.fetch_near_month(market)
    if near is None:
        return []
    current = today()
    if interval == "1d":
        daily = kis_futures.fetch_daily(near.iscd, current - timedelta(days=count * 2 + 10), current)
        return daily.candles if daily else []
    if interval == "1m":
        return _recent_futures_minutes(near.iscd)
    return []


def _recent_futures_minutes(iscd: str) -> list[kis_futures.FuturesBar]:
    """오늘부터 뒤로 밀며 최근 2거래일치.

    휴장일을 요청하면 KIS가 **직전 영업일 분봉을 준다** — 그래서 실제 반환된 날짜의 하루 전부터
    다음 회차를 조회한다. 종료시각은 항상 장 마감으로 고정한다.
    """
    bars: list[kis_futures.FuturesBar] = []
    day = today()
    collected = 0
    for _ in range(_MINUTE_DAYS + 5):
        if collected == _MINUTE_DAYS:
            break
        day_bars = _futures_minutes_of_day(iscd, day, _FUT_SESSION_END)
        if not day_bars:
            day -= timedelta(days=1)
            continue
        bars.extend(day_bars)
        collected += 1
        day = date.fromisoformat(day_bars[0].date) - timedelta(days=1)
    return sorted(bars, key=lambda b: b.date + b.time)


def _futures_minutes_of_day(iscd: str, day: date, end: time) -> list[kis_futures.FuturesBar]:
    """하루치 분봉 — 한 번에 102봉만 오므로 `end`부터 장 시작까지 뒤로 페이징."""
    by_time: dict[str, kis_futures.FuturesBar] = {}
    hour = end
    for _ in range(_MINUTE_PAGES):
        page = kis_futures.fetch_minute(iscd, day, hour)
        if not page:
            break
        for bar in page:
            by_time[bar.time] = bar
        earliest = time.fromisoformat(page[0].time)
        if earliest <= _FUT_SESSION_START:
            break
        hour = (datetime.combine(day, earliest) - timedelta(minutes=1)).time()
    return [by_time[t] for t in sorted(by_time)]


# ── 야간선물 ────────────────────────────────────────────────────────────

_NIGHT_END_HOUR = 6         # 야간 마감 06:00 — 이 시각 전이면 아직 어제 시작한 세션
_NIGHT_START_MIN = 18 * 60  # 18:00
_NIGHT_END_MIN = 29 * 60 + 59  # 29:59 = 익일 05:59
_NIGHT_PAGES = 8            # 세션 720분을 102봉씩 덮는 최대 페이지 수


@dataclass(frozen=True)
class NightFuturesQuote:
    """코스피 야간선물 시세. 값은 지수 포인트."""

    price: float
    change_rate: float
    day_close: float  # 직전 정규장 종가
    gap: float        # 정규장 종가 대비 갭(포인트) — 다음날 시초가 가늠용
    open: float
    high: float
    low: float
    volume: int
    open_interest: int
    open_interest_change: int


@ttl_cache("nightFuturesQuote", ttl_seconds=5, maxsize=1, skip_if=lambda r: r is None)
def night_futures_quote() -> NightFuturesQuote | None:
    """근월물 코드는 정규장 전광판에서 뽑은 것을 그대로 쓴다(전광판은 야간을 지원하지 않는다)."""
    near = kis_futures.fetch_near_month(Market.KOSPI)
    if near is None:
        return None
    p = kis_futures.fetch_price(near.iscd, kis_futures.NIGHT)
    if p is None:
        return None
    return NightFuturesQuote(
        price=p.price,
        change_rate=p.change_rate,
        day_close=p.prev_close,
        gap=p.price_change,
        open=p.open,
        high=p.high,
        low=p.low,
        volume=p.volume,
        open_interest=p.open_interest,
        open_interest_change=p.open_interest_change,
    )


@ttl_cache(
    "nightFuturesCandles",
    ttl_seconds=60,
    maxsize=4,
    key=lambda interval, count: f"{interval}:{count}",
    skip_if=is_empty,
)
def night_futures_candles(interval: str, count: int) -> list[kis_futures.FuturesBar]:
    near = kis_futures.fetch_near_month(Market.KOSPI)
    if near is None:
        return []
    current = today()
    if interval == "1d":
        daily = kis_futures.fetch_daily(
            near.iscd, current - timedelta(days=count * 2 + 10), current, kis_futures.NIGHT
        )
        return daily.candles if daily else []
    if interval == "1m":
        return _recent_night_session(near.iscd)
    return []


def _recent_night_session(iscd: str) -> list[kis_futures.FuturesBar]:
    """최근 야간 세션 하나의 분봉.

    세션 기준일은 18:00이 속한 날이라, 새벽(06:00 이전)에는 아직 **어제 세션이 진행 중**이다.
    """
    day = today()
    if now().hour < _NIGHT_END_HOUR:
        day -= timedelta(days=1)
    for _ in range(5):
        bars = _night_session_minutes(iscd, day)
        if bars:
            return bars
        day -= timedelta(days=1)
    return []


def _night_session_minutes(iscd: str, day: date) -> list[kis_futures.FuturesBar]:
    """`day` 18:00에 시작한 세션의 분봉 — 102봉씩 뒤로 페이징. 시각은 18:00~29:59(=익일 05:59)."""
    bars: dict[str, kis_futures.FuturesBar] = {}
    minute = _NIGHT_END_MIN
    for _ in range(_NIGHT_PAGES):
        page = kis_futures.fetch_minute(iscd, day, _hhmmss(minute), kis_futures.NIGHT)
        if not page:
            break
        for bar in page:
            bars[f"{bar.date} {bar.time}"] = bar
        earliest = _extended_minute(page[0])
        if earliest <= _NIGHT_START_MIN:
            break
        minute = earliest - 1
    return [bars[k] for k in sorted(bars)]


def _extended_minute(bar: kis_futures.FuturesBar) -> int:
    """봉 시각을 세션 기준(자정 넘김=+24시간)의 '분'으로 되돌린다 — 페이징 커서 계산용."""
    h, m = (int(x) for x in bar.time.split(":")[:2])
    hour = h + 24 if h < _NIGHT_END_HOUR else h  # 00~05시는 익일
    return hour * 60 + m


def _hhmmss(minute: int) -> str:
    """분 단위 시각을 KIS가 요구하는 HHMMSS로 — **24시 이상도 그대로** 보낸다(1530분 → "253000")."""
    return f"{minute // 60:02d}{minute % 60:02d}00"


# ── 나스닥 지수 · 매크로 (야후) ─────────────────────────────────────────


@dataclass(frozen=True)
class QuoteResult:
    price: float
    prev_close: float
    price_change: float
    change_rate: float


@dataclass(frozen=True)
class MacroQuotes:
    usd_krw: QuoteResult | None
    wti: QuoteResult | None
    vix: QuoteResult | None


NASDAQ_SYMBOL = "^IXIC"
# 매크로 대상 — 야후 심볼을 여기 한 곳에만 둔다.
MACRO_SYMBOLS = {"USD_KRW": "KRW=X", "WTI": "CL=F", "VIX": "^VIX"}


def _quote_of(symbol: str) -> QuoteResult | None:
    q = yahoo.fetch_quote(symbol)
    if q is None:
        return None
    change = q.price - q.prev_close
    return QuoteResult(
        price=q.price,
        prev_close=q.prev_close,
        price_change=change,
        change_rate=0.0 if q.prev_close == 0 else change / q.prev_close * 100,
    )


@ttl_cache("nasdaqIndexQuote", ttl_seconds=10, maxsize=1, skip_if=lambda r: r is None)
def nasdaq_quote() -> QuoteResult | None:
    """현물이라 미 정규장에만 움직인다 — 우리 장중엔 직전 마감가에 멈춰 있다."""
    return _quote_of(NASDAQ_SYMBOL)


@ttl_cache("nasdaqIndexCandles", ttl_seconds=60, maxsize=2, skip_if=is_empty)
def nasdaq_candles(interval: str) -> list:
    return _yahoo_candles(NASDAQ_SYMBOL, interval)


@ttl_cache("macroQuotes", ttl_seconds=10, maxsize=1)
def macro_quotes() -> MacroQuotes:
    """일부가 실패해도 나머지는 살려서 준다."""
    return MacroQuotes(
        usd_krw=_quote_of(MACRO_SYMBOLS["USD_KRW"]),
        wti=_quote_of(MACRO_SYMBOLS["WTI"]),
        vix=_quote_of(MACRO_SYMBOLS["VIX"]),
    )


@ttl_cache(
    "macroCandles",
    ttl_seconds=60,
    maxsize=6,
    key=lambda target, interval: f"{target}{interval}",
    skip_if=is_empty,
)
def macro_candles(target: str, interval: str) -> list:
    symbol = MACRO_SYMBOLS.get(target)
    return _yahoo_candles(symbol, interval) if symbol else []


def _yahoo_candles(symbol: str, interval: str) -> list:
    if interval == "1m":
        return yahoo.fetch_candles(symbol, "1m", "2d")
    if interval == "1d":
        return yahoo.fetch_candles(symbol, "1d", "6mo")
    return []
