"""모의 종가베팅 API — 공개 범위와 응답 모양."""

from datetime import date, datetime

import pytest

from backend.closingbet import application, presentation
from backend.closingbet.application import PoolStock, StockFlows, StockReading
from backend.closingbet.domain import BetRejected, Checks, Moment, NicknameRejected, Outcome, Phase, Result, Results, Stake, Stakes
from backend.closingbet.entities import BetPlayer, BetRound, BetRoundStock, ClosingBet
from backend.stock.domain import Market

목 = date(2026, 10, 1)
BET = Phase(Moment.BET, betting=목, review=date(2026, 9, 30), next_at=datetime(2026, 10, 1, 20))
HYNIX = PoolStock("000660", "SK하이닉스", True, 412_500, 6.82)
CHECKS = Checks.of(412_500, 414_000, 2_640, 910, 740, -0.4, -5.0)


@pytest.fixture
def 베팅_시간(mocker):
    mocker.patch.object(application, "phase", return_value=BET)
    mocker.patch.object(application, "pool", return_value=[HYNIX])
    mocker.patch.object(application, "stakes", return_value=Stakes([Stake(1, "000660", 2_000, datetime(2026, 10, 1, 15))]))
    reading = StockReading(HYNIX, Market.KOSPI, 412_500, 414_000, 386_000, StockFlows(2_640, 910, 8_420, 2_150), -0.4, CHECKS)
    mocker.patch.object(presentation, "_readings", return_value={"flows": {"KOSPI": {}}, "readings": {"000660": reading}, "nxt": {"000660": True}})


class Test지금:
    def test_방문자는_판돈과_참여_수까지(self, client, 베팅_시간):
        data = client.get("/api/closingbet/now").json()["data"]
        assert data == {
            "moment": "bet", "bettingDay": "2026-10-01", "reviewDay": "2026-09-30", "nextAt": "2026-10-01T20:00:00",
            "potMan": 2_000, "players": 1, "me": None,
        }

    def test_로그인하면_내_닉네임과_베팅이_실린다(self, 로그인_client, 베팅_시간, mocker):
        mocker.patch.object(application, "find_player", return_value=BetPlayer(user_id=7, nickname="용감한수달", renamed_at=None))
        mocker.patch.object(application, "my_bet", return_value=ClosingBet(stock_code="000660", stock_name="SK하이닉스", amount_man=2_000, status="open"))
        me = 로그인_client.get("/api/closingbet/now").json()["data"]["me"]
        assert me["nickname"] == "용감한수달" and me["nextRenameAt"] is None
        assert me["bet"]["stockCode"] == "000660" and me["bet"]["amountMan"] == 2_000

    def test_아직_한_번도_안_걸었으면_닉네임이_없다(self, 로그인_client, 베팅_시간, mocker):
        mocker.patch.object(application, "find_player", return_value=None)
        mocker.patch.object(application, "my_bet", return_value=None)
        assert 로그인_client.get("/api/closingbet/now").json()["data"]["me"] == {"nickname": None, "nextRenameAt": None, "bet": None}


class Test오늘의_종목:
    def test_방문자는_등급까지만_본다(self, client, 베팅_시간):
        [item] = client.get("/api/closingbet/stocks").json()["data"]
        assert (item["code"], item["grade"], item["crowd"], item["potMan"], item["market"], item["nxt"]) == ("000660", "S", 1, 2_000, "KOSPI", True)
        assert item["checks"] is None and item["foreign"] is None

    def test_로그인하면_등급_이유가_실린다(self, 로그인_client, 베팅_시간):
        [item] = 로그인_client.get("/api/closingbet/stocks").json()["data"]
        assert item["checks"] == {"nearHigh": True, "foreign": True, "institution": True, "marketLate": True, "recentHigh": True}
        assert (item["foreignWeek"], item["recentHighGap"], item["high"]) == (8_420, -0.4, 414_000)

    def test_시장_수급_표는_로그인_뒤다(self, client, 베팅_시간):
        assert client.get("/api/closingbet/market").status_code == 401


class Test베팅_API:
    def test_방문자는_걸_수_없다(self, client):
        assert client.put("/api/closingbet/bet", json={"code": "000660", "amountMan": 2_000}).status_code == 401

    def test_거절되면_이유_문장을_돌려준다(self, 로그인_client, mocker):
        mocker.patch.object(application, "place", side_effect=BetRejected("베팅은 15:00부터 20:00까지예요"))
        r = 로그인_client.put("/api/closingbet/bet", json={"code": "000660", "amountMan": 2_000})
        assert r.status_code == 409
        assert r.json() == {"code": "BET_REJECTED", "status": 409, "data": {"message": "베팅은 15:00부터 20:00까지예요"}}

    def test_종목_코드가_6자리가_아니면_받지_않는다(self, 로그인_client):
        r = 로그인_client.put("/api/closingbet/bet", json={"code": "000660_AL", "amountMan": 2_000})
        assert r.json()["code"] == "INVALID_PARAMETER"

    def test_걸면_내_베팅을_돌려준다(self, 로그인_client, mocker):
        place = mocker.patch.object(application, "place", return_value=ClosingBet(stock_code="000660", stock_name="SK하이닉스", amount_man=2_000, status="open"))
        data = 로그인_client.put("/api/closingbet/bet", json={"code": "000660", "amountMan": 2_000}).json()["data"]
        assert data["stockName"] == "SK하이닉스"
        assert place.call_args.args[1:4] == (7, "000660", 2_000)


class Test닉네임_API:
    def test_거절되면_이유_문장을_돌려준다(self, 로그인_client, mocker):
        mocker.patch.object(application, "rename", side_effect=NicknameRejected("이미 있는 닉네임이에요"))
        r = 로그인_client.put("/api/closingbet/me/nickname", json={"nickname": "새벽올빼미"})
        assert (r.status_code, r.json()["data"]["message"]) == (409, "이미 있는 닉네임이에요")


class Test복기_API:
    @pytest.fixture
    def 판(self, mocker):
        mocker.patch.object(application, "bet_round", return_value=BetRound(trading_day=목, players=2, pot_man=4_000, market={"KOSPI": {}}, settled_at=datetime(2026, 10, 2, 9, 5)))
        mocker.patch.object(application, "round_stocks", return_value=[BetRoundStock(
            trading_day=목, stock_code="000660", stock_name="SK하이닉스", market="KOSPI", lead=True, nxt=True,
            close_price=400_000, high_price=401_000, low_price=390_000, change_rate=3.1, foreign_net=100, institution_net=50,
            foreign_5d=500, institution_5d=200, recent_high_gap=-0.4, checks={"near_high": True, "foreign": True, "institution": True, "market_late": True, "recent_high": True},
            grade="S", crowd=2, pot_man=4_000, sell_price=408_000.0, rate=2.0,
        )])
        mocker.patch.object(application, "results", return_value=Results([
            Result(1, "000660", datetime(2026, 10, 1, 15), Outcome(400_000, 2.0)),
            Result(7, "000660", datetime(2026, 10, 1, 16), Outcome(400_000, 2.0)),
        ]))
        mocker.patch.object(application, "nicknames", return_value={1: "새벽올빼미", 7: "용감한수달"})

    def test_방문자는_결과와_랭킹을_본다(self, client, 판):
        data = client.get("/api/closingbet/rounds/2026-10-01").json()["data"]
        assert data["settled"] and data["stocks"][0]["rate"] == 2.0 and data["stocks"][0]["checks"] is None
        assert [(r["rank"], r["nickname"]) for r in data["byProfit"]] == [(1, "새벽올빼미"), (1, "용감한수달")]
        assert data["me"] is None and data["market"] is None

    def test_로그인하면_내_결과와_순위가_실린다(self, 로그인_client, 판, mocker):
        mocker.patch.object(application, "my_bet", return_value=ClosingBet(
            stock_code="000660", stock_name="SK하이닉스", amount_man=2_000, shares=50, buy_price=400_000, sell_price=408_000.0, pnl=400_000, rate=2.0,
        ))
        data = 로그인_client.get("/api/closingbet/rounds/2026-10-01").json()["data"]
        assert (data["me"]["pnl"], data["me"]["profitRank"]) == (400_000, 1)
        assert data["market"] == {"KOSPI": {}}

    def test_없는_판은_404(self, client, mocker):
        mocker.patch.object(application, "bet_round", return_value=None)
        assert client.get("/api/closingbet/rounds/2026-10-03").status_code == 404
