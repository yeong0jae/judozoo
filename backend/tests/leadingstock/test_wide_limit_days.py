"""제한폭이 넓은 날 표시 — 그날 한 번이라도 +30%를 넘은 종목은 그날 내내 표시가 붙는다."""

from datetime import date, datetime

import pytest

from backend.leadingstock import wide_limit_days
from backend.leadingstock.domain import LeadingStockSnapshot
from backend.leadingstock.entities import WideLimitDay
from backend.library import db

오늘, 내일 = date(2026, 9, 30), date(2026, 10, 1)
때 = datetime(2026, 9, 30, 9, 5)


def 종목(코드: str, 등락률: float) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=코드, stock_name=코드, current_price=1, price_change_rate=등락률,
        trading_value_rank=1, accumulated_trading_value=1,
    )


def 표시(stocks) -> dict[str, bool]:
    return {s.stock_code: s.wide_limit_day for s in stocks}


class Test표시:
    def test_30퍼센트를_넘은_종목에_표시를_붙인다(self):
        assert 표시(wide_limit_days.mark([종목("A_AL", 100.0), 종목("B_AL", 12.0)], 오늘, 때)) == {"A_AL": True, "B_AL": False}

    def test_한_번_넘었으면_내려와도_그날은_계속_붙는다(self):
        wide_limit_days.mark([종목("A_AL", 100.0)], 오늘, 때)

        assert 표시(wide_limit_days.mark([종목("A_AL", 26.0)], 오늘, 때)) == {"A_AL": True}

    def test_랭킹_코드와_맨_코드는_같은_종목이다(self):
        wide_limit_days.mark([종목("000001_AL", 100.0)], 오늘, 때)

        assert 표시(wide_limit_days.mark([종목("000001", 26.0)], 오늘, 때)) == {"000001": True}

    def test_날이_바뀌면_새로_센다(self):
        wide_limit_days.mark([종목("A_AL", 100.0)], 오늘, 때)

        assert 표시(wide_limit_days.mark([종목("A_AL", 26.0)], 내일, 때)) == {"A_AL": False}


@pytest.fixture
def 기록_DB(통합_db, monkeypatch):
    monkeypatch.setattr(wide_limit_days, "get_session_factory", db.get_session_factory)
    WideLimitDay.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(WideLimitDay).delete()
        s.commit()
    wide_limit_days.reset()
    yield
    wide_limit_days.reset()


@pytest.mark.integration
class Test재시작:
    def test_재시작해도_오늘_넘은_종목을_기억한다(self, 기록_DB):
        wide_limit_days.mark([종목("A_AL", 100.0)], 오늘, 때)
        wide_limit_days.reset()   # 재시작 — 메모리가 비었다

        assert 표시(wide_limit_days.mark([종목("A_AL", 26.0)], 오늘, 때)) == {"A_AL": True}

    def test_같은_종목을_여러_번_넘어도_한_번만_남긴다(self, 기록_DB):
        wide_limit_days.mark([종목("A_AL", 100.0)], 오늘, 때)
        wide_limit_days.reset()
        wide_limit_days.mark([종목("A_AL", 90.0)], 오늘, 때)

        with db.get_session_factory()() as s:
            assert s.query(WideLimitDay).count() == 1

    def test_일주일_지난_기록은_지운다(self, 기록_DB):
        wide_limit_days.mark([종목("A_AL", 100.0)], date(2026, 9, 20), 때)
        wide_limit_days.reset()

        wide_limit_days.mark([], 오늘, 때)

        with db.get_session_factory()() as s:
            assert s.query(WideLimitDay).count() == 0
