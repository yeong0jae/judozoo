"""주도주 캘린더 API — 공개 여부, 달 파라미터, 응답 모양."""

from datetime import date
from decimal import Decimal

import pytest

from backend.leadercalendar import application
from backend.leadercalendar.application import RecordedDay
from backend.leadercalendar.entities import LeaderDayStock


def 한_달(mocker, 국내=(), 해외=()):
    return mocker.patch.object(application, "find_month", return_value=(list(국내), list(해외)))


class Test주도주_캘린더_API:
    def test_로그인_없이_본다(self, client, mocker):
        한_달(mocker)

        응답 = client.get("/api/leader-calendar", params={"month": "2026-09"})

        assert 응답.json()["code"] == "SUCCESS"

    def test_요청한_달로_조회한다(self, client, mocker):
        조회 = 한_달(mocker)

        client.get("/api/leader-calendar", params={"month": "2026-09"})

        _, 연, 월 = 조회.call_args.args
        assert (연, 월) == (2026, 9)

    @pytest.mark.parametrize("month", ["2026-9", "2026-13", "2026-00", "202609", ""])
    def test_달_모양이_틀리면_거절한다(self, client, mocker, month):
        한_달(mocker)

        응답 = client.get("/api/leader-calendar", params={"month": month})

        assert 응답.json()["code"] == "INVALID_PARAMETER"

    def test_국내와_해외를_따로_날짜별로_준다(self, client, mocker):
        하이닉스 = LeaderDayStock(
            rank=1, exchange=None, code="000660", name="SK하이닉스",
            price=Decimal("250000"), change_rate=2.2, trading_value=Decimal("971000000000"),
        )
        한_달(mocker, 국내=[RecordedDay(date(2026, 9, 23), [하이닉스]), RecordedDay(date(2026, 9, 14), [])])

        데이터 = client.get("/api/leader-calendar", params={"month": "2026-09"}).json()["data"]

        assert 데이터["overseas"] == []
        assert 데이터["domestic"][0] == {
            "date": "2026-09-23",
            "stocks": [{
                "rank": 1, "exchange": None, "code": "000660", "name": "SK하이닉스",
                "price": 250000.0, "changeRate": 2.2, "tradingValue": 971000000000.0,
            }],
        }
        assert 데이터["domestic"][1] == {"date": "2026-09-14", "stocks": []}
