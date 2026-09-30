"""시황 — 지수 / 캔들 / 투자자 수급 / 프로그램매매 / 선물 / 매크로.

세션(오전·오후·마감) 순매수는 값을 직접 주는 API가 없다. 폴러가 찍어둔 **당일 누적 스냅샷의
경계 diff**로 만든다 — 이 계산이 이 모듈의 핵심이고, 경계 시각이 어긋나면 값이 통째로 틀린다.
"""

import logging
import re
from dataclasses import dataclass, replace
from datetime import date, datetime, time, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.leadingstock.infrastructure import MarketInvestorSnapshot, investor_snapshot_at
from backend.library.cache import is_empty, ttl_cache
from backend.library.time import KST, now, today
from backend.market import calendar, futures_minute_archive, minute_archive, yahoo_minute_archive
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


@ttl_cache("kospiIndex", ttl_seconds=40, maxsize=1, serve_stale=True)
def get_kospi() -> IndexResult:
    return _index_of(Market.KOSPI)


@ttl_cache("kosdaqIndex", ttl_seconds=40, maxsize=1, serve_stale=True)
def get_kosdaq() -> IndexResult:
    return _index_of(Market.KOSDAQ)


def _index_of(market: Market) -> IndexResult:
    code, mrkt_tp = _INDEX_CODE[market]
    snap = kiwoom_index.fetch_index(code, mrkt_tp)
    if snap is None:
        raise RuntimeError(f"{market.name} 지수 조회 실패")
    return IndexResult(current_value=snap.current_value, change_rate=snap.change_rate)


# ── 지수 캔들 (토스) ────────────────────────────────────────────────────

_MAX_PAGE_SIZE = 200
_MAX_PAGES = 6  # 200×6=1200봉 — 2일치 정규장(780분)을 여유 있게 덮는다


# 지수 캔들은 차트를 보는 사람마다, 1분봉은 30초마다 다시 묻는다. 캐시가 없으면 보는 사람 수만큼
# 토스를 부르고, 그룹 한도가 초당 5건이라 금방 닿는다. 30초면 1분봉 화면 주기와 같다.
@ttl_cache("indexDailyCandles", ttl_seconds=30, maxsize=4, skip_if=is_empty)
def daily_candles(market: Market, count: int) -> list[toss_indicator.TossCandle]:
    """최근 `count`봉 일봉(오름차순)."""
    page = toss_indicator.fetch_candles(market.name, "1d", max(1, min(count, _MAX_PAGE_SIZE)))
    return sorted(page.candles, key=lambda c: c.timestamp)


@ttl_cache("indexMinuteCandles", ttl_seconds=30, maxsize=2, skip_if=is_empty)
def minute_candles_today(market: Market) -> list[toss_indicator.TossCandle]:
    """가장 최근 2영업일의 1분봉(오름차순) — 여러 페이지를 모아 2일치를 덮는다."""
    current = today()
    by_day: dict[date, dict[datetime, toss_indicator.TossCandle]] = {}
    archived: set[date] = set()
    checked: set[date] = set()
    before: str | None = None
    exhausted = False

    for _ in range(_MAX_PAGES):
        page = toss_indicator.fetch_candles(market.name, "1m", _MAX_PAGE_SIZE, before)
        if not page.candles:
            # 외부 오류도 빈 페이지로 오므로 수집 완료로 취급하지 않는다.
            break
        for c in page.candles:
            day = c.timestamp.date()
            by_day.setdefault(day, {})[c.timestamp] = c
        for day in list(by_day):
            if day < current and day not in checked:
                checked.add(day)
                stored = minute_archive.get(market, day)
                if stored:
                    by_day[day] = {c.timestamp: c for c in stored}
                    archived.add(day)
        exhausted = page.next_before is None
        latest = sorted(by_day, reverse=True)[:2]
        # 두 번째 날이 DB에 완성본으로 있으면 더 과거 페이지는 필요 없다.
        if len(by_day) > 2 or exhausted or (len(latest) == 2 and latest[-1] in archived):
            break
        before = page.next_before

    if not by_day:
        return []
    earliest = min(by_day)
    for day, candles in by_day.items():
        # 더 이전 날짜를 실제로 보거나 끝까지 받은 날만 완성본이다.
        if day < current and day not in archived and (day > earliest or exhausted):
            minute_archive.put(market, day, list(candles.values()))
    valid = sorted(by_day, reverse=True)[:2]
    return sorted((c for day in valid for c in by_day[day].values()), key=lambda c: c.timestamp)


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
    #: 직전 스냅샷 대비 변화량. 마지막 스냅샷이 속한 구간에만 실린다(그리고 오늘만).
    delta: Nets | None = None


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


def _last_two(session: Session, model, market: Market, on: date) -> tuple[object, object]:
    """그날 마지막 스냅샷과 그 직전 것. 하나뿐이거나 없으면 그만큼 None."""
    rows = list(
        session.scalars(
            select(model)
            .where(model.market == market, model.trade_date == on)
            .order_by(model.captured_at.desc())
            .limit(2)
        )
    )
    return (rows[0] if rows else None, rows[1] if len(rows) > 1 else None)


def _attach_delta(sessions: list, session: Session, model, market: Market, on: date, today_: date):
    """마지막 스냅샷이 속한 구간에 "직전 스냅샷 대비" 변화량을 얹는다.

    **오늘만** 얹는다 — 지난 날짜의 "그날 마지막 1분 변화량"은 며칠 뒤에 볼 값이 아니다.
    구간 판정은 `SessionNet.time`("HH:MM~HH:MM")을 그대로 쓴다. 경계 시각을 두 번 적지 않게.
    """
    if on != today_:
        return sessions
    latest, prev = _last_two(session, model, market, on)
    if latest is None or prev is None:
        return sessions
    delta = latest.nets() - prev.nets()
    hm = latest.captured_at.strftime("%H:%M")
    return [
        replace(s, delta=delta)
        if s.nets is not None and s.time.split("~")[0] <= hm < s.time.split("~")[1]
        else s
        for s in sessions
    ]


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
    sessions = [
        SessionNet("프리마켓", "08:00~09:00", open_.nets() if open_ else None),
        SessionNet("오전", "09:00~12:00", morning_net),
        SessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
        SessionNet("마감 구간", "15:00~15:40", diff(close, afternoon)),
        SessionNet("애프터마켓", "15:40~20:00", diff(after_close, close)),
    ]
    return on, _attach_delta(sessions, session, MarketInvestorSnapshot, market, on, today())


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
    #: 직전 스냅샷 대비 변화량. `SessionNet.delta`와 같은 규칙이다.
    delta: FuturesNets | None = None


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

    sessions = [
        FuturesSessionNet("오전", "08:45~12:00", morning.nets() if morning else None),
        FuturesSessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
        FuturesSessionNet("마감 구간", "15:00~15:45", diff(close, afternoon)),
    ]
    return on, _attach_delta(sessions, session, FuturesInvestorSnapshot, market, on, today())


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


@ttl_cache("futuresQuote", ttl_seconds=40, maxsize=2, skip_if=lambda r: r is None, serve_stale=True)
def futures_quote(market: Market) -> FuturesQuote | None:
    near = kis_futures.fetch_near_month(market)
    if near is None:
        return None
    current = today()
    daily = kis_futures.fetch_daily(near.iscd, current - timedelta(days=10), current)
    if daily is None:
        return None
    s = daily.summary
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
    ttl_seconds=30,
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
        return _recent_futures_minutes(market, near.iscd)
    return []


def _recent_futures_minutes(market: Market, iscd: str) -> list[kis_futures.FuturesBar]:
    """오늘부터 뒤로 밀며 최근 2거래일치.

    휴장일을 요청하면 KIS가 **직전 영업일 분봉을 준다** — 그래서 실제 반환된 날짜의 하루 전부터
    다음 회차를 조회한다. 종료시각은 항상 장 마감으로 고정한다.
    """
    bars: list[kis_futures.FuturesBar] = []
    current = today()
    day = current
    if now().time() < _FUT_SESSION_START or calendar.is_open(current) is False:
        day = calendar.previous_open_day(current) or current
    collected = 0
    for _ in range(_MINUTE_DAYS + 5):
        if collected == _MINUTE_DAYS:
            break
        stored = futures_minute_archive.get(market, iscd, False, day) if day < current else None
        if stored:
            day_bars = stored
        else:
            day_bars, complete = _futures_minutes_of_day(iscd, day, _FUT_SESSION_END)
            if day_bars:
                actual = date.fromisoformat(day_bars[0].date)
                if actual < current:
                    archived = futures_minute_archive.get(market, iscd, False, actual)
                    if archived:
                        day_bars = archived
                    elif complete:
                        futures_minute_archive.put(market, iscd, False, actual, day_bars)
        if not day_bars:
            day -= timedelta(days=1)
            continue
        bars.extend(day_bars)
        collected += 1
        day = date.fromisoformat(day_bars[0].date) - timedelta(days=1)
    return sorted(bars, key=lambda b: b.date + b.time)


def _futures_minutes_of_day(
    iscd: str, day: date, end: time
) -> tuple[list[kis_futures.FuturesBar], bool]:
    """하루치 분봉 — 한 번에 102봉만 오므로 `end`부터 장 시작까지 뒤로 페이징."""
    by_time: dict[str, kis_futures.FuturesBar] = {}
    hour = end
    complete = False
    for _ in range(_MINUTE_PAGES):
        page = kis_futures.fetch_minute(iscd, day, hour)
        if not page:
            break
        for bar in page:
            by_time[bar.time] = bar
        earliest = time.fromisoformat(page[0].time)
        if earliest <= _FUT_SESSION_START:
            complete = True
            break
        hour = (datetime.combine(day, earliest) - timedelta(minutes=1)).time()
    return [by_time[t] for t in sorted(by_time)], complete


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


@ttl_cache("nightFuturesQuote", ttl_seconds=40, maxsize=1, skip_if=lambda r: r is None, serve_stale=True)
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
    ttl_seconds=30,
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
    at = now()
    day = at.date() if at.hour >= 18 else at.date() - timedelta(days=1)
    if calendar.is_open(day) is False:
        day = calendar.previous_open_day(day + timedelta(days=1)) or day
    for _ in range(5):
        completed = at >= datetime.combine(day + timedelta(days=1), time(_NIGHT_END_HOUR))
        stored = futures_minute_archive.get(Market.KOSPI, iscd, True, day) if completed else None
        if stored:
            return stored
        bars, complete = _night_session_minutes(iscd, day)
        if bars:
            first = bars[0]
            actual = date.fromisoformat(first.date)
            if first.time < f"{_NIGHT_END_HOUR:02d}:00:00":
                actual -= timedelta(days=1)
            if actual != day:
                archived = futures_minute_archive.get(Market.KOSPI, iscd, True, actual)
                if archived:
                    return archived
            if complete and at >= datetime.combine(actual + timedelta(days=1), time(_NIGHT_END_HOUR)):
                futures_minute_archive.put(Market.KOSPI, iscd, True, actual, bars)
            return bars
        day -= timedelta(days=1)
    return []


def _night_session_minutes(iscd: str, day: date) -> tuple[list[kis_futures.FuturesBar], bool]:
    """`day` 18:00에 시작한 세션의 분봉 — 102봉씩 뒤로 페이징. 시각은 18:00~29:59(=익일 05:59)."""
    bars: dict[str, kis_futures.FuturesBar] = {}
    minute = _NIGHT_END_MIN
    complete = False
    for _ in range(_NIGHT_PAGES):
        page = kis_futures.fetch_minute(iscd, day, _hhmmss(minute), kis_futures.NIGHT)
        if not page:
            break
        for bar in page:
            bars[f"{bar.date} {bar.time}"] = bar
        earliest = _extended_minute(page[0])
        if earliest <= _NIGHT_START_MIN:
            complete = True
            break
        minute = earliest - 1
    return [bars[k] for k in sorted(bars)], complete


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
    us10y: QuoteResult | None


NASDAQ_SYMBOL = "^IXIC"
#: 나스닥100 선물 근월물. 야후가 `NQ=F` 하나로 근월물을 물려준다 — 만기별 코드를 조립할 일이 없다.
#: KIS는 CME가 유료시세라 못 쓴다.
NASDAQ_FUTURES_SYMBOL = "NQ=F"
# 매크로 대상 — 야후 심볼을 여기 한 곳에만 둔다.
# `^TNX`는 미국채 10년 금리를 **% 그대로** 준다(5.209 = 5.209%). CBOE 원 지수처럼 ×10이 아니다.
MACRO_SYMBOLS = {"USD_KRW": "KRW=X", "WTI": "CL=F", "VIX": "^VIX", "US10Y": "^TNX"}


@ttl_cache("yahooQuotes", ttl_seconds=40, maxsize=6, skip_if=lambda r: r is None, serve_stale=True)
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


def nasdaq_quote() -> QuoteResult | None:
    """현물이라 미 정규장에만 움직인다 — 우리 장중엔 직전 마감가에 멈춰 있다."""
    return _quote_of(NASDAQ_SYMBOL)


def nasdaq_candles(interval: str) -> list:
    if interval == "1m":
        return _nasdaq_minute_candles()
    if interval == "1d":
        return _nasdaq_daily_candles()
    return []


@ttl_cache("nasdaqIndexDailyCandles", ttl_seconds=60, maxsize=1, skip_if=is_empty)
def _nasdaq_daily_candles() -> list[yahoo.YahooBar]:
    return yahoo.fetch_candles(NASDAQ_SYMBOL, "1d", "6mo")


_YAHOO_ROLLOVER = {"KRW=X": time(17), "CL=F": time(18)}


def _ny_at(bar: yahoo.YahooBar) -> datetime:
    at = datetime.fromisoformat(f"{bar.date}T{bar.time}").replace(tzinfo=KST)
    return at.astimezone(calendar.Region.US.zone).replace(tzinfo=None)


def _yahoo_session_day(symbol: str, at: datetime) -> date:
    """환율·WTI는 야간 개장일을 다음 미국 거래일에, 현물 지표는 현지 날짜에 묶는다."""
    rollover = _YAHOO_ROLLOVER.get(symbol)
    return at.date() + timedelta(days=1) if rollover and at.time() >= rollover else at.date()


def _complete_yahoo_day(symbol: str, day: date, bars: list[yahoo.YahooBar]) -> bool:
    """조회 창이 세션 양 끝을 덮은 날만 DB에 확정한다."""
    if not bars:
        return False
    if symbol in _YAHOO_ROLLOVER:
        rollover = _YAHOO_ROLLOVER[symbol]
        start = datetime.combine(day - timedelta(days=1), rollover) + timedelta(minutes=30)
        # WTI는 17시부터 한 시간 휴장한다. 마지막 분봉은 18시 재개장보다 앞선다.
        end = datetime.combine(day, rollover) - timedelta(minutes=90 if symbol == "CL=F" else 30)
    else:
        start_at, end_at = (time(8, 20), time(15)) if symbol == "^TNX" else (time(9, 30), time(15, 59))
        grace = timedelta() if symbol == NASDAQ_SYMBOL else timedelta(minutes=30)
        start = datetime.combine(day, start_at) + grace
        end = datetime.combine(day, end_at) - grace
    times = [_ny_at(bar) for bar in bars]
    return min(times) <= start and max(times) >= end


@ttl_cache("nasdaqIndexMinuteCandles", ttl_seconds=30, maxsize=1, skip_if=is_empty)
def _nasdaq_minute_candles() -> list[yahoo.YahooBar]:
    return _yahoo_minute_candles(NASDAQ_SYMBOL)


def _yahoo_minute_candles(symbol: str) -> list[yahoo.YahooBar]:
    """진행 중인 미국 세션은 야후에서 갱신하고 완성된 최근 세션은 DB에서 읽는다."""
    current = _yahoo_session_day(symbol, now().replace(tzinfo=KST).astimezone(calendar.Region.US.zone))
    archived = yahoo_minute_archive.recent(symbol, current)
    previous = current - timedelta(days=1)
    while previous.weekday() >= 5:
        previous -= timedelta(days=1)
    # 직전 거래일이 DB에 있으면 야후에는 최신 하루만 묻는다. 휴일이면 2일로 폴백한다.
    window = "1d" if previous in archived else "2d"
    fresh = yahoo.fetch_candles(symbol, "1m", window)
    by_day: dict[date, list[yahoo.YahooBar]] = {}
    for bar in fresh:
        by_day.setdefault(_yahoo_session_day(symbol, _ny_at(bar)), []).append(bar)
    for day, bars in by_day.items():
        if day < current and day not in archived and _complete_yahoo_day(symbol, day, bars):
            yahoo_minute_archive.put(symbol, day, bars)
            archived[day] = bars
    by_day.update(archived)
    latest = sorted(by_day, reverse=True)[:2]
    return sorted((bar for day in latest for bar in by_day[day]), key=lambda bar: bar.date + bar.time)


def nasdaq_futures_quote() -> QuoteResult | None:
    """현물과 달리 거의 하루 종일 돈다 — **우리 장중에 미국 심리를 읽는 자리**다.

    야후 무료 시세라 **10분쯤 지연**된다. 그래서 화면이 지연 배지를 함께 건다.
    """
    return _quote_of(NASDAQ_FUTURES_SYMBOL)


@ttl_cache("nasdaqFuturesCandles", ttl_seconds=60, maxsize=2, skip_if=is_empty)
def nasdaq_futures_candles(interval: str) -> list:
    if interval == "1m":
        return yahoo.fetch_candles(NASDAQ_FUTURES_SYMBOL, "1m", "1d")
    return _yahoo_candles(NASDAQ_FUTURES_SYMBOL, interval)


def macro_quotes() -> MacroQuotes:
    """일부가 실패해도 나머지는 살려서 준다."""
    return MacroQuotes(
        usd_krw=_quote_of(MACRO_SYMBOLS["USD_KRW"]),
        wti=_quote_of(MACRO_SYMBOLS["WTI"]),
        vix=_quote_of(MACRO_SYMBOLS["VIX"]),
        us10y=_quote_of(MACRO_SYMBOLS["US10Y"]),
    )


def macro_candles(target: str, interval: str) -> list:
    symbol = MACRO_SYMBOLS.get(target)
    if symbol is None:
        return []
    if interval == "1m":
        return _macro_minute_candles(symbol)
    if interval == "1d":
        return _macro_daily_candles(symbol)
    return []


@ttl_cache("macroMinuteCandles", ttl_seconds=30, maxsize=4, skip_if=is_empty)
def _macro_minute_candles(symbol: str) -> list[yahoo.YahooBar]:
    return _yahoo_minute_candles(symbol)


@ttl_cache("macroDailyCandles", ttl_seconds=60, maxsize=4, skip_if=is_empty)
def _macro_daily_candles(symbol: str) -> list[yahoo.YahooBar]:
    return yahoo.fetch_candles(symbol, "1d", "6mo")


def _yahoo_candles(symbol: str, interval: str) -> list:
    if interval == "1m":
        return yahoo.fetch_candles(symbol, "1m", "2d")
    if interval == "1d":
        return yahoo.fetch_candles(symbol, "1d", "6mo")
    return []


# ── 홈 — 오늘의 수급 ────────────────────────────────────────────────────


@dataclass(frozen=True)
class TodayNetInvestors:
    """투자자 넷 — 현물은 억원, 선물은 계약이다."""

    individual: int
    foreign: int
    institution: int
    other_corp: int


@dataclass(frozen=True)
class TodayNet:
    """지수 한 칸의 당일 누적 순매수."""

    market: Market
    futures: bool
    index_value: float
    change_rate: float
    #: 수급만 못 받았을 때 None. **칸을 지우지 않는다** — KIS가 코스닥150 선물 투자자
    #: 조회에 간헐적으로 500을 주는데, 그때마다 칸이 사라지면 카드가 넷이었다 셋이었다 한다.
    #: 가격·등락률은 멀쩡히 왔으므로 그것만 보여주고 수급 줄은 화면이 비운다.
    nets: TodayNetInvestors | None


def today_nets(session: Session) -> list[TodayNet]:
    """코스피·코스닥 현물과 두 지수선물의 당일 누적 순매수.

    현물(억원)과 선물(계약)은 단위가 달라 한 목록에 담겨도 서로 더하거나 견주지 않는다.

    현물·선물 수급은 각각의 폴러가 저장한 오늘의 최신 스냅샷을 쓴다. 아직 스냅샷이
    없으면 시세만 보여주고 수급은 비운다.

    시세조차 못 받은 시장만 목록에서 빠진다. 수급만 비는 경우는 `nets=None`으로 남는다.
    """
    out: list[TodayNet] = []
    for market in Market:
        snapshot = investor_snapshot_at(session, market, now())
        if snapshot is None:
            try:
                quote = get_kospi() if market == Market.KOSPI else get_kosdaq()
            except RuntimeError:
                continue
        out.append(
            TodayNet(
                market=market, futures=False,
                index_value=snapshot.index_value if snapshot else quote.current_value,
                change_rate=snapshot.change_rate if snapshot else quote.change_rate,
                nets=None if snapshot is None else TodayNetInvestors(
                    individual=snapshot.individual_eok, foreign=snapshot.foreign_eok,
                    institution=snapshot.institution_eok, other_corp=snapshot.other_corp_eok,
                ),
            )
        )
    for market in Market:
        quote = futures_quote(market)
        if quote is None:
            continue
        snapshot = _futures_snapshot_at(session, market, now())
        out.append(
            TodayNet(
                market=market, futures=True,
                index_value=quote.futures_price, change_rate=quote.change_rate,
                nets=None if snapshot is None else TodayNetInvestors(
                    individual=snapshot.individual_qty, foreign=snapshot.foreign_qty,
                    institution=snapshot.institution_qty, other_corp=snapshot.other_corp_qty,
                ),
            )
        )
    return out
