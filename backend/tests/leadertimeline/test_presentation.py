"""주도주 타임라인 API — 공개 여부, 파라미터, 응답 모양."""

from datetime import date, datetime
from decimal import Decimal

import pytest

from backend.leadertimeline import application
from backend.leadertimeline.application import RecordedTick
from backend.leadertimeline.entities import LeaderTickStock
from backend.market.calendar import Region


def 종목(rank: int, code: str, name: str, rate: float) -> LeaderTickStock:
    return LeaderTickStock(
        rank=rank, exchange=None, code=code, name=name,
        price=Decimal("1000"), change_rate=rate, trading_value=Decimal("100000000000"),
    )


@pytest.fixture
def 하루(mocker):
    조회 = mocker.patch.object(application, "find_day", return_value=[
        RecordedTick(datetime(2026, 9, 23, 10, 15), [종목(1, "000660", "SK하이닉스", 2.2), 종목(2, "005930", "삼성전자", 0.7)]),
        RecordedTick(datetime(2026, 9, 23, 10, 16), [종목(1, "005930", "삼성전자", 0.8)]),
        RecordedTick(datetime(2026, 9, 23, 10, 17), []),
    ])
    mocker.patch.object(application, "last_taken_at", return_value=datetime(2026, 9, 23, 10, 17, 1))
    return 조회


class Test주도주_타임라인_API:
    def test_로그인_없이_본다(self, client, 하루):
        assert client.get("/api/leader-timeline", params={"market": "kr", "date": "2026-09-23"}).json()["code"] == "SUCCESS"

    def test_종목은_사전으로_한_번만_싣고_분마다_번호로_준다(self, client, 하루):
        데이터 = client.get("/api/leader-timeline", params={"market": "kr", "date": "2026-09-23"}).json()["data"]

        assert 데이터["stocks"] == [
            {"exchange": None, "code": "000660", "name": "SK하이닉스"},
            {"exchange": None, "code": "005930", "name": "삼성전자"},
        ]
        assert 데이터["ticks"] == [
            {"at": "10:15", "stocks": [0, 1], "rates": [2.2, 0.7], "values": [100000000000.0, 100000000000.0]},
            {"at": "10:16", "stocks": [1], "rates": [0.8], "values": [100000000000.0]},
            {"at": "10:17", "stocks": [], "rates": [], "values": []},
        ]
        assert 데이터["lastTakenAt"] == "2026-09-23T10:17:01"

    def test_시장과_since를_그대로_넘긴다(self, client, 하루):
        client.get("/api/leader-timeline", params={"market": "us", "date": "2026-09-23", "since": "10:59"})

        _, 시장, 날, 뒤 = 하루.call_args.args
        assert (시장, 날, 뒤) == (Region.US, date(2026, 9, 23), datetime(2026, 9, 23, 10, 59))

    @pytest.mark.parametrize("params", [
        {"market": "jp", "date": "2026-09-23"},
        {"market": "kr", "date": "2026-9-23"},
        {"market": "kr"},
        {"market": "kr", "date": "2026-09-23", "since": "25:00"},
    ])
    def test_모양이_틀린_요청은_거절한다(self, client, 하루, params):
        assert client.get("/api/leader-timeline", params=params).json()["code"] == "INVALID_PARAMETER"
