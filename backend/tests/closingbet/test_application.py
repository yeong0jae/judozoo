"""모의 종가베팅 — 베팅을 받고, 20:00에 체결하고, 아침에 파는가.

주도주 풀·시세·수급·개장일은 바깥이라 mock으로 바꾸고, DB는 컨테이너 MySQL을 쓴다.
"""

from datetime import date, datetime, time

import pytest

from backend.closingbet import application
from backend.closingbet.domain import BetRejected, NicknameRejected
from backend.closingbet.entities import BetPlayer, BetRound, BetRoundStock, ClosingBet, ClosingBetEvent
from backend.leadingstock.domain import DailyCandle, LeadingStockSnapshot, MinuteCandle
from backend.library import db
from backend.stock.application import StockInvestorDay, StockOrgBreakdown
from backend.stock.domain import Market

목 = date(2026, 10, 1)
금 = date(2026, 10, 2)
TABLES = (BetPlayer, ClosingBet, ClosingBetEvent, BetRound, BetRoundStock)


def kst(day: date, h: int, m: int = 0) -> datetime:
    return datetime.combine(day, time(h, m))


def 종목(code: str, name: str, price: int) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=f"{code}_AL", stock_name=name, current_price=price, price_change_rate=5.0,
        trading_value_rank=1, accumulated_trading_value=1,
    )


def 일봉(code: str, n: int, base: date) -> list[DailyCandle]:
    """오늘 봉(종가 = 20:00 체결) + 과거 250봉. 하이닉스는 신고가, 나머지는 평범."""
    close = {"000660": 400_000, "247540": 200_000, "277810": 300_000}[code]
    today_ = DailyCandle(base, close, close, close - 10_000, close, 1, 5.0)
    past_high = close - 1 if code == "000660" else close * 2
    past = [DailyCandle(date(2025, 1, 1).replace(day=1 + i % 28), 0, past_high, 0, 0, 1, 0.0) for i in range(250)]
    return [today_, *past]


def 수급(code: str, count: int) -> list[StockInvestorDay]:
    org = StockOrgBreakdown(0, 0, 0, 0, 0, 0, 0)
    sign = 1 if code == "000660" else -1
    return [StockInvestorDay(목, 0, sign * 100, sign * 50, 0, org)]


FLOWS = {
    "KOSPI": {"foreign": [10, 50, 1, 5, 30, 5], "institution": [-5, -10, -1, -5, 10, -2]},
    "KOSDAQ": {"foreign": [1, 2, 1, 2, -30, -5], "institution": [1, 2, 1, 2, -10, -2]},
    "night": {"price": 413.6, "rate": 0.35},
}


@pytest.fixture
def 빈_테이블(통합_db):
    for t in TABLES:
        t.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        for t in TABLES:
            s.query(t).delete()
        s.commit()


@pytest.fixture
def 바깥(mocker):
    pool = {"leaders": [종목("000660", "SK하이닉스", 412_500)], "candidates": [종목("247540", "에코프로비엠", 198_400), 종목("277810", "레인보우로보틱스", 341_500)]}
    mocker.patch.object(application.leadingstock, "find_leaders", side_effect=lambda n: pool["leaders"])
    mocker.patch.object(application.leadingstock, "find_candidate_stocks", side_effect=lambda r: pool["candidates"])
    mocker.patch.object(application.calendar, "open_days", return_value=frozenset({date(2026, 9, 30), 목, 금, date(2026, 10, 5)}))
    mocker.patch.object(application.kiwoom_market, "fetch_daily_candles", side_effect=일봉)
    mocker.patch.object(application.kiwoom_market, "fetch_nxt_listed", side_effect=lambda code: code != "277810")
    mocker.patch.object(application.stock_app, "investor_daily_history", side_effect=수급)
    mocker.patch.object(application.stock_app, "market_of", side_effect=lambda code: Market.KOSPI if code == "000660" else Market.KOSDAQ)
    mocker.patch.object(application, "market_flows", return_value=FLOWS)
    return pool


def 세션():
    return db.get_session_factory()()


def 분봉(day: date, hh: int, mm: int, high: int, low: int) -> MinuteCandle:
    return MinuteCandle(kst(day, hh, mm), low, high, low, high, 10, 0)


@pytest.mark.integration
class Test베팅:
    def test_걸고_바꾸고_취소하면_LIVE에_차례로_남는다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 1, "000660", 2_000, kst(목, 15, 10))
            application.place(s, 1, "247540", 2_000, kst(목, 15, 20))
            application.cancel(s, 1, kst(목, 15, 30))
            assert application.my_bet(s, 1, 목) is None
            feed = application.feed(s, 목)
        assert [(f.stock_name, f.amount_man) for f in feed] == [("에코프로비엠", -2_000), ("에코프로비엠", 2_000), ("SK하이닉스", -2_000), ("SK하이닉스", 2_000)]

    def test_처음_걸면_랜덤_닉네임이_생긴다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 7, "000660", 100, kst(목, 16))
            assert s.get(BetPlayer, 7).nickname

    def test_하루에_한_종목만_남는다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 1, "000660", 2_000, kst(목, 15, 10))
            application.place(s, 1, "247540", 3_000, kst(목, 15, 20))
            bets = s.query(ClosingBet).all()
        assert [(b.stock_code, b.amount_man) for b in bets] == [("247540", 3_000)]

    def test_20시가_되면_받지_않는다(self, 빈_테이블, 바깥):
        with 세션() as s, pytest.raises(BetRejected):
            application.place(s, 1, "000660", 2_000, kst(목, 20, 0))

    def test_지금_목록에_없는_종목은_못_고른다(self, 빈_테이블, 바깥):
        with 세션() as s, pytest.raises(BetRejected, match="목록"):
            application.place(s, 1, "005930", 2_000, kst(목, 16))

    def test_판돈과_참여_수(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 1, "000660", 2_000, kst(목, 15, 10))
            application.place(s, 2, "000660", 500, kst(목, 15, 11))
            st = application.stakes(s, 목)
        assert (st.total_man, st.players, st.by_stock()) == (2_500, 2, {"000660": 2_500})


@pytest.mark.integration
class Test20시_체결:
    def test_종가로_사고_판을_그대로_찍어_둔다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 1, "000660", 2_000, kst(목, 15, 10))
            assert application.fill_round(s, 목, kst(목, 20, 1))
            bet = application.my_bet(s, 1, 목)
            stocks = {r.stock_code: r for r in application.round_stocks(s, 목)}
            round_ = application.bet_round(s, 목)
        assert (bet.status, bet.buy_price, bet.shares) == ("filled", 400_000, 50)
        hynix = stocks["000660"]
        assert (hynix.grade, hynix.level, hynix.nxt, hynix.crowd, hynix.pot_man) == ("S", "신고가", True, 1, 2_000)
        assert stocks["277810"].nxt is False
        assert (round_.players, round_.pot_man, round_.market) == (1, 2_000, FLOWS)

    def test_목록에서_빠진_종목에_건_베팅도_체결한다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 1, "247540", 1_000, kst(목, 15, 10))
            바깥["candidates"] = []
            application.fill_round(s, 목, kst(목, 20, 1))
            bet = application.my_bet(s, 1, 목)
            codes = {r.stock_code for r in application.round_stocks(s, 목)}
        assert bet.status == "filled" and "247540" in codes

    def test_한_주도_못_사게_오르면_판에서_뺀다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.place(s, 1, "277810", 100, kst(목, 15, 10))
            # 걸 때 가격으로는 살 수 있었는데 종가로는 못 사는 경우 — 금액을 직접 낮춰 만든다
            s.query(ClosingBet).update({"amount_man": 20})
            s.commit()
            application.fill_round(s, 목, kst(목, 20, 1))
            assert application.my_bet(s, 1, 목).status == "void"

    def test_두_번_불러도_한_번만_찍는다(self, 빈_테이블, 바깥):
        with 세션() as s:
            application.fill_round(s, 목, kst(목, 20, 1))
            application.fill_round(s, 목, kst(목, 20, 2))
            assert len(application.round_stocks(s, 목)) == 3


@pytest.mark.integration
class Test아침_정산:
    @pytest.fixture
    def 체결된_판(self, 빈_테이블, 바깥, mocker):
        with 세션() as s:
            application.place(s, 1, "000660", 2_000, kst(목, 15, 10))
            application.place(s, 2, "277810", 2_000, kst(목, 15, 20))
            application.fill_round(s, 목, kst(목, 20, 1))
        bars = {
            "000660": [분봉(금, 8, 0, 410_000, 406_000)],
            "247540": [분봉(금, 8, 1, 201_000, 199_000)],
            "277810": [분봉(금, 9, 2, 306_000, 300_000)],
        }
        return mocker.patch.object(application.kiwoom_market, "fetch_historical_minute_candles", side_effect=lambda code, day: bars[code])

    def test_NXT_종목은_08시05분에_먼저_판다(self, 체결된_판):
        with 세션() as s:
            assert not application.settle_round(s, 목, 금, kst(금, 8, 5))
            hynix = application.my_bet(s, 1, 목)
            rainbow = application.my_bet(s, 2, 목)
        assert (hynix.status, hynix.sell_price, hynix.pnl) == ("settled", 408_000.0, 50 * 8_000)
        assert rainbow.status == "filled"

    def test_09시05분에_나머지를_팔고_랭킹을_확정한다(self, 체결된_판):
        with 세션() as s:
            application.settle_round(s, 목, 금, kst(금, 8, 5))
            assert application.settle_round(s, 목, 금, kst(금, 9, 7))
            ranked = application.results(s, 목).ranked(by_rate=False)
            round_ = application.bet_round(s, 목)
        assert round_.settled_at == kst(금, 9, 7)
        # 하이닉스 50주 × +8,000 = 40만 / 레인보우 66주 × +3,000 = 19.8만
        assert [(x.rank, x.result.user_id, x.result.outcome.pnl) for x in ranked] == [(1, 1, 400_000), (2, 2, 198_000)]


@pytest.mark.integration
class Test닉네임_바꾸기:
    def test_남이_쓰는_닉네임은_못_쓴다(self, 빈_테이블):
        with 세션() as s:
            s.add(BetPlayer(user_id=1, nickname="새벽올빼미", created_at=kst(목, 9)))
            s.commit()
            with pytest.raises(NicknameRejected, match="이미"):
                application.rename(s, 2, "새벽올빼미", kst(목, 10))

    def test_바꾸면_7일_잠긴다(self, 빈_테이블):
        with 세션() as s:
            application.rename(s, 1, "수달왕", kst(목, 10))
            with pytest.raises(NicknameRejected, match="10/8"):
                application.rename(s, 1, "수달여왕", kst(금, 10))
            assert s.get(BetPlayer, 1).nickname == "수달왕"
