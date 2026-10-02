"""모의 종가베팅 API.

방문자는 판돈·LIVE·목록(등급)·랭킹까지 본다. 베팅·닉네임과 **등급 이유·시장 수급 표**는 로그인 뒤다 —
투자자별 수급은 이 서비스가 가공한 값이라 다른 화면과 같이 로그인 뒤에 둔다(PRD).
"""

from datetime import date, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from sqlalchemy.orm import Session

from backend.auth.domain import CurrentUser
from backend.auth.presentation import current_user, require_login
from backend.closingbet import application
from backend.closingbet.domain import BetRejected, NicknameRejected, next_rename_at
from backend.library.cache import ttl_cache
from backend.library.db import get_db, get_session_factory
from backend.library.time import now
from backend.library.web import ApiResponse
from backend.platform.kiwoom import market as kiwoom_market

router = APIRouter(prefix="/api/closingbet")


class _Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


def _member_id(request: Request) -> int | None:
    user = current_user(request)
    return user.id if user else None


def _require_id(user: CurrentUser) -> int:
    if user.id is None:  # 로그인 전에 발급된 옛 세션 — 다시 로그인하면 채워진다
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")
    return user.id


def _rejected(code: str, exc: Exception) -> JSONResponse:
    """거절 이유는 화면에 그대로 보여 줄 문장이다 — 봉투 모양은 그대로, `data.message`에 싣는다."""
    return JSONResponse(status_code=409, content={"code": code, "status": 409, "data": {"message": str(exc)}})


# --- 지금 ---------------------------------------------------------------------


class MyBet(_Camel):
    stock_code: str
    stock_name: str
    amount_man: int
    status: str
    buy_price: int | None
    shares: int | None


class Me(_Camel):
    #: 첫 베팅 때 생긴다 — 그 전에는 None
    nickname: str | None
    next_rename_at: datetime | None
    bet: MyBet | None


class Now(_Camel):
    moment: str
    #: 베팅을 받거나 체결을 기다리는 판
    betting_day: date | None
    #: 복기·결과 발표에 보여 줄 판
    review_day: date | None
    next_at: datetime | None
    pot_man: int
    players: int
    #: 로그인일 때만
    me: Me | None = None


@router.get("/now")
def get_now(request: Request, db: Annotated[Session, Depends(get_db)]) -> ApiResponse[Now]:
    at = now()
    p = application.phase(at)
    stakes = application.stakes(db, p.betting) if p.betting else None
    me = None
    if (user_id := _member_id(request)) is not None:
        player = application.find_player(db, user_id)
        bet = application.my_bet(db, user_id, p.betting) if p.betting else None
        me = Me(
            nickname=player.nickname if player else None,
            next_rename_at=next_rename_at(player.renamed_at) if player else None,
            bet=MyBet(
                stock_code=bet.stock_code, stock_name=bet.stock_name, amount_man=bet.amount_man,
                status=bet.status, buy_price=bet.buy_price, shares=bet.shares,
            ) if bet else None,
        )
    return ApiResponse.ok(Now(
        moment=p.moment.value, betting_day=p.betting, review_day=p.review, next_at=p.next_at,
        pot_man=stakes.total_man if stakes else 0, players=stakes.players if stakes else 0, me=me,
    ))


# --- 오늘의 종목 ----------------------------------------------------------------


class CheckItem(_Camel):
    near_high: bool
    foreign: bool
    institution: bool
    market_late: bool
    recent_high: bool


class StockItem(_Camel):
    code: str
    name: str
    lead: bool
    price: int
    change_rate: float
    #: KOSPI / KOSDAQ. 등급을 못 매긴 종목은 None
    market: str | None
    #: NXT 상장 — 매도 창(08:00 / 09:00)을 가른다. 못 받았으면 None
    nxt: bool | None
    grade: str | None
    crowd: int
    pot_man: int
    #: 로그인일 때만 — 등급 이유
    high: int | None = None
    low: int | None = None
    foreign: int | None = None  # 백만원
    institution: int | None = None
    #: 최근 5일 합
    foreign_week: int | None = None
    institution_week: int | None = None
    #: 최근 고점과의 거리(%)
    recent_high_gap: float | None = None
    checks: CheckItem | None = None


@ttl_cache("closingbetReadings", ttl_seconds=30, maxsize=2, key=lambda day: day)
def _readings(day: date) -> dict:
    """오늘 목록의 등급 — 종목마다 일봉·수급을 한 번씩 부르니 30초 묶어 둔다(목록 폴링이 여럿이어도 한 번)."""
    with get_session_factory()() as session:
        flows = application.market_flows(session, day)
    late = application.market_late(flows)
    stocks = application.pool()
    return {
        "flows": flows,
        "readings": {s.code: application.read_stock(s, day, late) for s in stocks},
        "nxt": {s.code: kiwoom_market.fetch_nxt_listed(s.code) for s in stocks},
    }


@router.get("/stocks")
def get_stocks(request: Request, db: Annotated[Session, Depends(get_db)]) -> ApiResponse[list[StockItem]]:
    """베팅·대기 시간의 오늘 목록. 그 밖의 시간에는 빈 목록 — 복기는 `/rounds/{day}`가 맡는다."""
    p = application.phase()
    if p.betting is None:
        return ApiResponse.ok([])
    member = _member_id(request) is not None
    stakes = application.stakes(db, p.betting)
    pots, crowd = stakes.by_stock(), stakes.crowd()
    cached = _readings(p.betting)
    readings = cached["readings"]
    items = []
    for s in application.pool():
        r = readings.get(s.code)
        item = StockItem(
            code=s.code, name=s.name, lead=s.lead, price=s.price, change_rate=s.change_rate,
            market=r.market.value if r else None, nxt=cached["nxt"].get(s.code),
            grade=r.checks.grade.value if r else None, crowd=crowd.get(s.code, 0), pot_man=pots.get(s.code, 0),
        )
        if member and r:
            item.high, item.low = r.high, r.low
            item.foreign, item.institution = r.flows.foreign, r.flows.institution
            item.foreign_week, item.institution_week = r.flows.foreign_5d, r.flows.institution_5d
            item.recent_high_gap = r.recent_high_gap
            item.checks = CheckItem(**application.checks_json(r.checks))
        items.append(item)
    return ApiResponse.ok(items)


@router.get("/market")
def get_market(_: Annotated[CurrentUser, Depends(require_login)]) -> ApiResponse[dict | None]:
    """오늘 시장 수급 표(로그인). 베팅·대기 시간 밖이면 None."""
    p = application.phase()
    return ApiResponse.ok(_readings(p.betting)["flows"] if p.betting else None)


# --- LIVE ---------------------------------------------------------------------


class FeedEntry(_Camel):
    id: int
    nickname: str
    stock_name: str
    amount_man: int
    at: datetime


@router.get("/feed")
def get_feed(db: Annotated[Session, Depends(get_db)], after: int = Query(0, ge=0)) -> ApiResponse[list[FeedEntry]]:
    p = application.phase()
    if p.betting is None:
        return ApiResponse.ok([])
    return ApiResponse.ok([FeedEntry(**f.__dict__) for f in application.feed(db, p.betting, after)])


# --- 베팅 ---------------------------------------------------------------------


class BetRequest(BaseModel):
    code: str = Field(pattern=r"^\d{6}$")
    amount_man: int = Field(alias="amountMan")


@router.put("/bet", response_model=None)
def put_bet(
    body: BetRequest,
    user: Annotated[CurrentUser, Depends(require_login)],
    db: Annotated[Session, Depends(get_db)],
) -> ApiResponse[MyBet] | JSONResponse:
    try:
        bet = application.place(db, _require_id(user), body.code, body.amount_man, now())
    except BetRejected as exc:
        return _rejected("BET_REJECTED", exc)
    return ApiResponse.ok(MyBet(
        stock_code=bet.stock_code, stock_name=bet.stock_name, amount_man=bet.amount_man,
        status=bet.status, buy_price=bet.buy_price, shares=bet.shares,
    ))


@router.delete("/bet", response_model=None)
def delete_bet(
    user: Annotated[CurrentUser, Depends(require_login)],
    db: Annotated[Session, Depends(get_db)],
) -> ApiResponse[None] | JSONResponse:
    try:
        application.cancel(db, _require_id(user), now())
    except BetRejected as exc:
        return _rejected("BET_REJECTED", exc)
    return ApiResponse.ok(None)


# --- 닉네임 -------------------------------------------------------------------


class NicknameRequest(BaseModel):
    nickname: str = Field(max_length=40)


@router.put("/me/nickname", response_model=None)
def put_nickname(
    body: NicknameRequest,
    user: Annotated[CurrentUser, Depends(require_login)],
    db: Annotated[Session, Depends(get_db)],
) -> ApiResponse[Me] | JSONResponse:
    try:
        p = application.rename(db, _require_id(user), body.nickname, now())
    except NicknameRejected as exc:
        return _rejected("NICKNAME_REJECTED", exc)
    return ApiResponse.ok(Me(nickname=p.nickname, next_rename_at=next_rename_at(p.renamed_at), bet=None))


# --- 복기 ---------------------------------------------------------------------


class RoundStock(_Camel):
    code: str
    name: str
    lead: bool
    nxt: bool
    close_price: int
    change_rate: float
    market: str
    grade: str
    crowd: int
    pot_man: int
    #: 공개 전이면 None
    sell_price: float | None
    rate: float | None
    #: 로그인일 때만
    high: int | None = None
    low: int | None = None
    foreign: int | None = None
    institution: int | None = None
    #: 최근 5일 합
    foreign_week: int | None = None
    institution_week: int | None = None
    recent_high_gap: float | None = None
    checks: CheckItem | None = None


class RankRow(_Camel):
    rank: int
    nickname: str
    stock_name: str
    pnl: int
    rate: float


class MyResult(_Camel):
    stock_code: str
    stock_name: str
    amount_man: int
    shares: int | None
    buy_price: int | None
    sell_price: float | None
    pnl: int | None
    rate: float | None
    profit_rank: int | None
    rate_rank: int | None


class Round(_Camel):
    day: date
    settled: bool
    players: int
    pot_man: int
    stocks: list[RoundStock]
    by_profit: list[RankRow]
    by_rate: list[RankRow]
    #: 로그인일 때만
    market: dict | None = None
    me: MyResult | None = None


@router.get("/rounds/{day}")
def get_round(day: date, request: Request, db: Annotated[Session, Depends(get_db)]) -> ApiResponse[Round]:
    round_ = application.bet_round(db, day)
    if round_ is None:
        raise HTTPException(status_code=404, detail="NOT_FOUND")
    user_id = _member_id(request)
    stocks = application.round_stocks(db, day)
    names = {s.stock_code: s.stock_name for s in stocks}
    results = application.results(db, day)
    nicks = application.nicknames(db, {r.result.user_id for r in results.ranked(False)})

    def rows(by_rate: bool) -> list[RankRow]:
        return [
            RankRow(rank=x.rank, nickname=nicks.get(x.result.user_id, "?"), stock_name=names.get(x.result.stock_code, x.result.stock_code),
                    pnl=x.result.outcome.pnl, rate=x.result.outcome.rate)
            for x in results.top(by_rate)
        ]

    out = Round(
        day=day, settled=round_.settled_at is not None, players=round_.players, pot_man=round_.pot_man,
        stocks=[_round_stock(s, member=user_id is not None) for s in stocks],
        by_profit=rows(False), by_rate=rows(True),
    )
    if user_id is not None:
        out.market = round_.market
        if bet := application.my_bet(db, user_id, day):
            profit, rate = results.rank_of(user_id, False), results.rank_of(user_id, True)
            out.me = MyResult(
                stock_code=bet.stock_code, stock_name=bet.stock_name, amount_man=bet.amount_man, shares=bet.shares,
                buy_price=bet.buy_price, sell_price=bet.sell_price, pnl=bet.pnl, rate=bet.rate,
                profit_rank=profit.rank if profit else None, rate_rank=rate.rank if rate else None,
            )
    return ApiResponse.ok(out)


def _round_stock(s, member: bool) -> RoundStock:
    item = RoundStock(
        code=s.stock_code, name=s.stock_name, lead=s.lead, nxt=s.nxt, close_price=s.close_price,
        change_rate=s.change_rate, market=s.market, grade=s.grade, crowd=s.crowd, pot_man=s.pot_man,
        sell_price=s.sell_price, rate=s.rate,
    )
    if member:
        item.high, item.low = s.high_price, s.low_price
        item.foreign, item.institution = s.foreign_net, s.institution_net
        item.foreign_week, item.institution_week = s.foreign_5d, s.institution_5d
        item.recent_high_gap = s.recent_high_gap
        item.checks = CheckItem(**s.checks)
    return item
