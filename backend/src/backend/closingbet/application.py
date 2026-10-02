"""모의 종가베팅 — 베팅을 받고, 20:00에 체결하고, 아침에 판다.

판정은 전부 `domain`이 한다. 여기서는 시세·수급을 모으고, 행을 읽고 쓴다.
주도주 풀은 `leadingstock`, 시장·선물 수급은 `market`, 종목 수급은 `stock`의 application에서 받는다.
"""

import logging
import random
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.closingbet.domain import (
    BET_CLOSE,
    REGULAR_CLOSE,
    RESULT_START,
    BetRejected,
    Checks,
    NicknameRejected,
    OpenDays,
    Outcome,
    Phase,
    Result,
    Results,
    Stake,
    Stakes,
    check_bet,
    check_nickname,
    check_rename,
    phase_at,
    recent_high_gap,
    regular_close,
    random_nickname,
    reveal_at,
    sell_price,
    shares_for,
)
from backend.closingbet.entities import BetPlayer, BetRound, BetRoundStock, ClosingBet, ClosingBetEvent
from backend.leadingstock import application as leadingstock
from backend.leadingstock.domain import DailyCandle
from backend.library.cache import ttl_cache
from backend.library.time import now
from backend.market import application as market_app
from backend.market import calendar
from backend.platform.kiwoom import market as kiwoom_market
from backend.settings import get_settings
from backend.stock import application as stock_app
from backend.stock.domain import Market

log = logging.getLogger(__name__)

LEADERS_COUNT = 5
#: 후보 등락률 하한(%) — 주도주 목록 화면의 기본값(+7%)보다 넓게, 오른 종목이면 다 고를 수 있게
CANDIDATE_MIN_CHANGE_RATE = 0.0
#: 최근 고점 60거래일 + 오늘
_DAILY_COUNT = 61
_FLOW_DAYS = 5

_BANNED: tuple[str, ...] = ()


# --- 시간대 -------------------------------------------------------------------


def open_days() -> OpenDays:
    """개장일. 목록을 못 받았으면 앞뒤 30일 평일로 — 휴장 화면이 멀쩡한 평일을 덮지 않게."""
    days = calendar.open_days()
    if days:
        return OpenDays(days)
    today_ = now().date()
    around = (today_ + timedelta(days=d) for d in range(-30, 31))
    return OpenDays(d for d in around if d.weekday() < 5)


def phase(at: datetime | None = None) -> Phase:
    return phase_at(at or now(), open_days())


# --- 오늘의 종목 ----------------------------------------------------------------


@dataclass(frozen=True)
class PoolStock:
    code: str
    name: str
    lead: bool
    price: int
    change_rate: float


def pool() -> list[PoolStock]:
    """주도주 5 + 후보(오늘 0% 이상 오른 나머지, 거래대금 순). 거래대금 35위·ETF·스팩 거르기는 주도주 목록과 같다."""
    leaders = leadingstock.find_leaders(LEADERS_COUNT)
    lead = {_code(s.stock_code) for s in leaders}
    candidates = leadingstock.find_candidate_stocks(CANDIDATE_MIN_CHANGE_RATE)
    out = [_pool_stock(s, True) for s in leaders]
    out += [_pool_stock(s, False) for s in candidates if _code(s.stock_code) not in lead]
    return out


def _pool_stock(s, lead: bool) -> PoolStock:
    return PoolStock(_code(s.stock_code), s.stock_name, lead, s.current_price, s.price_change_rate)


def _code(stock_code: str) -> str:
    return stock_code.split("_", 1)[0]


# --- 종베 체크 ----------------------------------------------------------------


@dataclass(frozen=True)
class StockFlows:
    """종목 수급(백만원). 당일과 최근 5일 합 — 통합(KRX+NXT)."""

    foreign: int
    institution: int
    foreign_5d: int
    institution_5d: int


@dataclass(frozen=True)
class StockReading:
    stock: PoolStock
    market: Market
    close: int
    high: int
    low: int
    flows: StockFlows
    #: 최근 고점과의 거리(%) — 직전 60거래일 최고가 대비
    recent_high_gap: float | None
    #: 15:30 정규장 종가. 정규장이 끝나기 전이면 None
    regular_close: int | None
    checks: Checks


def read_stock(stock: PoolStock, on: date, market_late: dict[Market, int]) -> StockReading | None:
    """한 종목의 종베 체크. 일봉 한 번(오늘 + 52주)과 수급 한 번. 못 받으면 None — 그 종목은 등급 없이 둔다."""
    market = stock_app.market_of(stock.code)
    candles = kiwoom_market.fetch_daily_candles(stock.code, _DAILY_COUNT, on)
    today_bar = next((c for c in candles if c.date == on), None)
    if market is None or today_bar is None:
        log.warning("종베 체크 재료 없음 %s market=%s 오늘봉=%s", stock.code, market, today_bar is not None)
        return None
    history: Sequence[DailyCandle] = [c for c in candles if c.date < on]
    flows = _stock_flows(stock.code, on)
    gap = recent_high_gap(history, today_bar.close_price)
    regular = _regular_close(stock.code, on)
    checks = Checks.of(
        price=today_bar.close_price, high=today_bar.high_price,
        foreign_net=flows.foreign, institution_net=flows.institution,
        market_late_net=market_late.get(market, 0),
        recent_high_gap=gap, recent_high_floor=get_settings().criteria.max_high_position_drop_rate,
        regular_close=regular,
    )
    return StockReading(stock, market, today_bar.close_price, today_bar.high_price, today_bar.low_price, flows, gap, regular, checks)


@ttl_cache("closingbetRegularClose", ttl_seconds=24 * 60 * 60, maxsize=200, skip_if=lambda v: v is None)
def _regular_close(code: str, on: date) -> int | None:
    """정규장 종가는 15:30에 한 번 정해지면 안 바뀐다 — 잡히면 하루 들고 있고, 그 전에는 분봉을 부르지 않는다."""
    if now() < datetime.combine(on, REGULAR_CLOSE):
        return None
    return regular_close(kiwoom_market.fetch_historical_minute_candles(code, on), on)


def _stock_flows(code: str, on: date) -> StockFlows:
    days = stock_app.investor_daily_history(code, _FLOW_DAYS)
    today_row = next((d for d in days if d.date == on), None)
    return StockFlows(
        foreign=today_row.foreign_million if today_row else 0,
        institution=today_row.institution_million if today_row else 0,
        foreign_5d=sum(d.foreign_million for d in days),
        institution_5d=sum(d.institution_million for d in days),
    )


# --- 시장 수급 ----------------------------------------------------------------


def market_flows(session: Session, on: date) -> dict:
    """시장 수급 표 — 코스피·코스닥 × 외인·기관 × [현물 당일, 현물 5일, 선물 당일, 선물 5일, 마감, 애프터](억원·계약) + 야간선물."""
    out: dict = {}
    for market in (Market.KOSPI, Market.KOSDAQ):
        spot = market_app.investor_daily_history(market, _FLOW_DAYS)
        spot_today = next((d for d in spot if d.date == on), None)
        fut = market_app.futures_investor_daily_history(session, market, _FLOW_DAYS)
        fut_today = next((d for d in fut if d.date == on), None)
        _, sessions = market_app.investor_sessions(session, market, on)
        close = next((s.nets for s in sessions if s.name == "마감 구간"), None)
        after = next((s.nets for s in sessions if s.name == "애프터마켓"), None)

        def row(spot_v, fut_v, attr: str) -> list[int]:
            return [
                getattr(spot_today, spot_v) if spot_today else 0,
                sum(getattr(d, spot_v) for d in spot),
                getattr(fut_today.nets, fut_v) if fut_today else 0,
                sum(getattr(d.nets, fut_v) for d in fut),
                getattr(close, attr) if close else 0,
                getattr(after, attr) if after else 0,
            ]

        out[market.value] = {
            "foreign": row("foreign_eok", "foreign", "foreign"),
            "institution": row("institution_eok", "institution", "institution"),
        }
    night = market_app.night_futures_quote()
    out["night"] = {"price": night.price, "rate": night.change_rate} if night else None
    return out


def market_late(flows: dict) -> dict[Market, int]:
    """시장 막판 — 마감 + 애프터 구간의 외인 + 기관 합(억원)."""
    return {
        m: sum(flows[m.value][who][4] + flows[m.value][who][5] for who in ("foreign", "institution"))
        for m in (Market.KOSPI, Market.KOSDAQ)
    }


# --- 닉네임 -------------------------------------------------------------------


def find_player(session: Session, user_id: int) -> BetPlayer | None:
    return session.get(BetPlayer, user_id)


def player(session: Session, user_id: int, at: datetime) -> BetPlayer:
    """처음이면 랜덤 닉네임으로 만든다. 동시에 같은 닉네임이 나오면 한 번 더 뽑는다."""
    existing = session.get(BetPlayer, user_id)
    if existing:
        return existing
    for _ in range(3):
        taken = set(session.scalars(select(BetPlayer.nickname)))
        row = BetPlayer(user_id=user_id, nickname=random_nickname(taken, random.Random()), created_at=at)
        session.add(row)
        try:
            session.commit()
            return row
        except IntegrityError:
            session.rollback()
            if found := session.get(BetPlayer, user_id):
                return found
    raise RuntimeError("닉네임을 만들지 못했다")


def rename(session: Session, user_id: int, raw: str, at: datetime) -> BetPlayer:
    me = player(session, user_id, at)
    check_rename(me.renamed_at, at)
    nick = check_nickname(raw, _BANNED)
    if nick == me.nickname:
        return me
    if session.scalar(select(BetPlayer).where(BetPlayer.nickname == nick)):
        raise NicknameRejected("이미 있는 닉네임이에요")
    me.nickname, me.renamed_at = nick, at
    try:
        session.commit()
    except IntegrityError as exc:
        session.rollback()
        raise NicknameRejected("이미 있는 닉네임이에요") from exc
    return me


# --- 베팅 ---------------------------------------------------------------------


def my_bet(session: Session, user_id: int, day: date) -> ClosingBet | None:
    return session.scalar(select(ClosingBet).where(ClosingBet.user_id == user_id, ClosingBet.trading_day == day))


def place(session: Session, user_id: int, code: str, amount_man: int, at: datetime) -> ClosingBet:
    """베팅·변경. 목록과 가격은 **그 순간** 풀로 검증한다 — 뒤에 목록에서 빠져도 베팅은 그대로."""
    p = phase(at)
    stocks = {s.code: s for s in pool()}
    stock = stocks.get(code)
    check_bet(p, amount_man, code, stocks, stock.price if stock else 0)
    if stock is None or p.betting is None:  # check_bet이 이미 막는다 — 타입을 좁힐 뿐
        raise BetRejected("지금 주도주·후보 목록에 있는 종목만 고를 수 있어요")
    player(session, user_id, at)
    bet = my_bet(session, user_id, p.betting)
    if bet and (bet.stock_code, bet.amount_man) == (code, amount_man):
        return bet
    if bet is None:
        bet = ClosingBet(user_id=user_id, trading_day=p.betting)
        session.add(bet)
    elif bet.stock_code != code:
        session.add(_event(bet.trading_day, user_id, bet.stock_name, -bet.amount_man, at))
    bet.stock_code, bet.stock_name, bet.amount_man, bet.placed_at, bet.status = code, stock.name, amount_man, at, "open"
    session.add(_event(p.betting, user_id, stock.name, amount_man, at))
    session.commit()
    return bet


def cancel(session: Session, user_id: int, at: datetime) -> None:
    p = phase(at)
    if not p.accepts_bets or p.betting is None:
        raise BetRejected("베팅은 15:00부터 20:00까지 바꾸거나 취소할 수 있어요")
    bet = my_bet(session, user_id, p.betting)
    if bet is None:
        return
    session.add(_event(bet.trading_day, user_id, bet.stock_name, -bet.amount_man, at))
    session.delete(bet)
    session.commit()


def _event(day: date, user_id: int, stock_name: str, amount_man: int, at: datetime) -> ClosingBetEvent:
    return ClosingBetEvent(trading_day=day, user_id=user_id, stock_name=stock_name, amount_man=amount_man, at=at)


def stakes(session: Session, day: date) -> Stakes:
    rows = session.scalars(select(ClosingBet).where(ClosingBet.trading_day == day, ClosingBet.status != "void"))
    return Stakes(Stake(r.user_id, r.stock_code, r.amount_man, r.placed_at) for r in rows)


@dataclass(frozen=True)
class FeedItem:
    id: int
    nickname: str
    stock_name: str
    amount_man: int
    at: datetime


def feed(session: Session, day: date, after_id: int = 0, limit: int = 20) -> list[FeedItem]:
    """LIVE — 최신이 앞."""
    rows = session.execute(
        select(ClosingBetEvent, BetPlayer.nickname)
        .join(BetPlayer, BetPlayer.user_id == ClosingBetEvent.user_id)
        .where(ClosingBetEvent.trading_day == day, ClosingBetEvent.id > after_id)
        .order_by(ClosingBetEvent.id.desc())
        .limit(limit)
    )
    return [FeedItem(e.id, nick, e.stock_name, e.amount_man, e.at) for e, nick in rows]


# --- 매분 ---------------------------------------------------------------------


def tick(session: Session, at: datetime) -> None:
    """개장일 08~20시 매분. 20:00이 지나면 오늘 판을 체결하고, 아침부터는 직전 판을 판다.

    둘 다 끝났으면 행 하나 읽고 바로 돌아온다.
    """
    days = open_days()
    today_ = at.date()
    if not days.is_open(today_):
        return
    previous = days.previous(today_)
    if previous is not None and at.time() >= RESULT_START:
        settle_round(session, previous, today_, at)
    if at.time() >= BET_CLOSE:
        fill_round(session, today_, at)


# --- 20:00 체결 · 스냅샷 -------------------------------------------------------


def fill_round(session: Session, day: date, at: datetime) -> bool:
    """판을 찍고 걸린 베팅에 매수가·주식 수를 넣는다. 이미 찍었으면 아무것도 안 한다.

    매수가는 **일봉 종가**(= 20:00 마지막 체결, 027 0단계). 일봉이 아직 오늘 값이 아니면 False — 몇 분 뒤 다시.
    """
    if session.get(BetRound, day) is not None:
        return True
    flows = market_flows(session, day)
    late = market_late(flows)
    current = stakes(session, day)
    pots, crowd = current.by_stock(), current.crowd()
    readings = [r for s in pool() if (r := read_stock(s, day, late)) is not None]
    bet_codes = {r.stock_code for r in session.scalars(select(ClosingBet).where(ClosingBet.trading_day == day))}
    # 목록에서 빠진 종목에 걸린 베팅 — 그 종목도 판에 넣는다(복기·정산에 필요)
    missing = bet_codes - {r.stock.code for r in readings}
    for code in missing:
        name = session.scalar(select(ClosingBet.stock_name).where(ClosingBet.trading_day == day, ClosingBet.stock_code == code).limit(1))
        if r := read_stock(PoolStock(code, name or code, False, 0, 0.0), day, late):
            readings.append(r)
    by_code = {r.stock.code: r for r in readings}
    if bet_codes - set(by_code):
        log.warning("종가베팅 체결 보류 — 종가 없는 종목 %s", bet_codes - set(by_code))
        return False

    for r in readings:
        nxt = kiwoom_market.fetch_nxt_listed(r.stock.code)
        session.add(BetRoundStock(
            trading_day=day, stock_code=r.stock.code, stock_name=r.stock.name, market=r.market.value,
            lead=r.stock.lead, nxt=bool(nxt), close_price=r.close, high_price=r.high, low_price=r.low,
            change_rate=r.stock.change_rate, foreign_net=r.flows.foreign, institution_net=r.flows.institution,
            foreign_5d=r.flows.foreign_5d, institution_5d=r.flows.institution_5d,
            recent_high_gap=r.recent_high_gap, regular_close=r.regular_close, checks=checks_json(r.checks), grade=r.checks.grade.value,
            crowd=crowd.get(r.stock.code, 0), pot_man=pots.get(r.stock.code, 0),
        ))
    for bet in session.scalars(select(ClosingBet).where(ClosingBet.trading_day == day, ClosingBet.status == "open")):
        price = by_code[bet.stock_code].close
        try:
            bet.buy_price, bet.shares, bet.status = price, shares_for(bet.amount_man, price), "filled"
        except BetRejected:
            # 걸 때보다 올라 한 주도 못 산다 — 판에서 뺀다
            bet.buy_price, bet.shares, bet.status = price, 0, "void"
    session.add(BetRound(trading_day=day, snapped_at=at, market=flows, players=current.players, pot_man=current.total_man))
    session.commit()
    log.info("종가베팅 체결 %s — %d종목, %d명", day, len(readings), current.players)
    return True


def checks_json(c: Checks) -> dict:
    return {"near_high": c.near_high, "foreign": c.foreign, "institution": c.institution, "market_late": c.market_late, "recent_high": c.recent_high, "after_hold": c.after_hold}


# --- 아침 매도 · 정산 -----------------------------------------------------------

#: 그날 끝까지 체결이 없으면(거래정지) 이 시각에 매수가로 끝낸다
_GIVE_UP_AT = BET_CLOSE


def settle_round(session: Session, day: date, sell_day: date, at: datetime) -> bool:
    """매도 창이 끝난 종목부터 판다. 판의 모든 종목이 끝나면 True(랭킹 확정)."""
    round_ = session.get(BetRound, day)
    if round_ is None or round_.settled_at is not None:
        return round_ is not None
    stocks = list(session.scalars(select(BetRoundStock).where(BetRoundStock.trading_day == day)))
    for s in stocks:
        if s.rate is not None or at < reveal_at(sell_day, s.nxt):
            continue
        bars = kiwoom_market.fetch_historical_minute_candles(s.stock_code, sell_day)
        got = sell_price(bars, sell_day, s.nxt, s.close_price)
        if got.window_start is None and at.time() < _GIVE_UP_AT:
            continue  # 아직 첫 체결이 없다 — 다음에 다시
        if got.window_start is not None and at < got.window_start + timedelta(minutes=5):
            continue  # 미뤄진 5분이 아직 안 끝났다
        s.sell_price, s.sell_window_start = got.price, got.window_start
        s.rate = Outcome.of(1, s.close_price, got.price).rate
        for bet in session.scalars(select(ClosingBet).where(ClosingBet.trading_day == day, ClosingBet.stock_code == s.stock_code, ClosingBet.status == "filled")):
            o = Outcome.of(bet.shares or 0, bet.buy_price or 0, got.price)
            bet.sell_price, bet.pnl, bet.rate, bet.status = got.price, o.pnl, o.rate, "settled"
    if all(s.rate is not None for s in stocks):
        round_.settled_at = at
    session.commit()
    return round_.settled_at is not None


def results(session: Session, day: date) -> Results:
    rows = session.scalars(select(ClosingBet).where(ClosingBet.trading_day == day, ClosingBet.status == "settled"))
    return Results(Result(r.user_id, r.stock_code, r.placed_at, Outcome(r.pnl or 0, r.rate or 0.0)) for r in rows)


def nicknames(session: Session, user_ids: set[int]) -> dict[int, str]:
    if not user_ids:
        return {}
    return dict(session.execute(select(BetPlayer.user_id, BetPlayer.nickname).where(BetPlayer.user_id.in_(user_ids))).tuples())


def round_stocks(session: Session, day: date) -> list[BetRoundStock]:
    return list(session.scalars(select(BetRoundStock).where(BetRoundStock.trading_day == day)))


def bet_round(session: Session, day: date) -> BetRound | None:
    return session.get(BetRound, day)
