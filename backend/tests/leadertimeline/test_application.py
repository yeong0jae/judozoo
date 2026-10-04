"""주도주 타임라인 — 1분 스냅샷이 남기는 것과 하루 조회."""

from datetime import date, datetime

import pytest

from backend.leadertimeline import application
from backend.leadertimeline.entities import LeaderTick, LeaderTickStock
from backend.leadingstock.domain import LeadingStockSnapshot
from backend.library import db
from backend.market.calendar import Region
from backend.overseasleadingstock.domain import OverseasStockRank

그날 = date(2026, 9, 23)
TAKEN = datetime(2026, 9, 23, 10, 15, 1)


def 분(h: int, m: int) -> datetime:
    return datetime(2026, 9, 23, h, m)


def 국내(code: str, name: str) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=code, stock_name=name, current_price=70_000, price_change_rate=5.0,
        trading_value_rank=1, accumulated_trading_value=1_234_500_000_000,
    )


@pytest.fixture
def 빈_타임라인(통합_db):
    engine = db.get_engine()
    LeaderTick.__table__.create(engine, checkfirst=True)
    LeaderTickStock.__table__.create(engine, checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(LeaderTickStock).delete()
        s.query(LeaderTick).delete()
        s.commit()


def 세션():
    return db.get_session_factory()()


def 국내_주도주(mocker, stocks):
    return mocker.patch.object(application.leadingstock, "find_leaders", return_value=stocks)


@pytest.mark.integration
class Test1분_스냅샷:
    def test_그_분의_주도주를_순서대로_남긴다(self, 빈_타임라인, mocker):
        국내_주도주(mocker, [국내("000660", "SK하이닉스"), 국내("009150_AL", "삼성전기")])

        with 세션() as s:
            application.snapshot(s, Region.KR, 분(10, 15), TAKEN)
        with 세션() as s:
            ticks = application.find_day(s, Region.KR, 그날)

        assert [(t.at, t.taken_at) for t in ticks] == [(분(10, 15), TAKEN)]
        assert [(x.rank, x.code, x.name) for x in ticks[0].stocks] == [(1, "000660", "SK하이닉스"), (2, "009150", "삼성전기")]

    def test_주도주가_없는_분도_분은_남는다(self, 빈_타임라인, mocker):
        국내_주도주(mocker, [])

        with 세션() as s:
            application.snapshot(s, Region.KR, 분(14, 25), TAKEN)
        with 세션() as s:
            ticks = application.find_day(s, Region.KR, 그날)

        assert [(t.at, t.stocks) for t in ticks] == [(분(14, 25), [])]

    def test_같은_분을_다시_찍으면_한_벌만_남는다(self, 빈_타임라인, mocker):
        국내_주도주(mocker, [국내("000660", "SK하이닉스"), 국내("005930", "삼성전자")])
        with 세션() as s:
            application.snapshot(s, Region.KR, 분(10, 15), TAKEN)
        국내_주도주(mocker, [국내("042700", "한미반도체")])
        with 세션() as s:
            application.snapshot(s, Region.KR, 분(10, 15), TAKEN)
        with 세션() as s:
            ticks = application.find_day(s, Region.KR, 그날)

        assert len(ticks) == 1
        assert [x.name for x in ticks[0].stocks] == ["한미반도체"]

    def test_주도주를_못_받으면_그_분은_남지_않는다(self, 빈_타임라인, mocker):
        """0개로 남기면 브로커 오류가 "주도주 없음"으로 굳는다."""
        mocker.patch.object(application.leadingstock, "find_leaders", side_effect=RuntimeError("키움 오류"))

        with 세션() as s, pytest.raises(RuntimeError):
            application.snapshot(s, Region.KR, 분(10, 15), TAKEN)
        with 세션() as s:
            assert application.find_day(s, Region.KR, 그날) == []

    def test_해외는_거래소와_달러_값을_남긴다(self, 빈_타임라인, mocker):
        mocker.patch.object(application.overseasleadingstock, "get_leaders", return_value=[OverseasStockRank(
            rank=1, exchange="NAS", symbol="NVDA", name="엔비디아", ename="NVIDIA",
            price=182.37, diff=3.0, rate=2.6, trading_value=44_300_000_000.5,
        )])

        with 세션() as s:
            application.snapshot(s, Region.US, 분(11, 30), TAKEN)
        with 세션() as s:
            종목 = application.find_day(s, Region.US, 그날)[0].stocks[0]

        assert (종목.exchange, 종목.code) == ("NAS", "NVDA")
        assert float(종목.trading_value) == 44_300_000_000.5


@pytest.mark.integration
class Test하루_조회:
    def test_시각_순으로_주고_since면_그_뒤만_준다(self, 빈_타임라인, mocker):
        국내_주도주(mocker, [국내("000660", "SK하이닉스")])
        with 세션() as s:
            for m in (17, 15, 16):
                application.snapshot(s, Region.KR, 분(10, m), TAKEN)

        with 세션() as s:
            전부 = [t.at for t in application.find_day(s, Region.KR, 그날)]
            뒤만 = [t.at for t in application.find_day(s, Region.KR, 그날, since=분(10, 15))]

        assert 전부 == [분(10, 15), 분(10, 16), 분(10, 17)]
        assert 뒤만 == [분(10, 16), 분(10, 17)]

    def test_시장이_다른_날은_섞이지_않는다(self, 빈_타임라인, mocker):
        국내_주도주(mocker, [국내("000660", "SK하이닉스")])
        with 세션() as s:
            application.snapshot(s, Region.KR, 분(10, 15), TAKEN)
        with 세션() as s:
            assert application.find_day(s, Region.US, 그날) == []


@pytest.mark.integration
class Test마지막_분:
    def test_그날_가장_늦은_분을_순위_순으로_준다(self, 빈_타임라인, mocker):
        국내_주도주(mocker, [국내("005930", "삼성전자")])
        with 세션() as s:
            application.snapshot(s, Region.KR, 분(10, 15), TAKEN)
        국내_주도주(mocker, [국내("000660", "SK하이닉스"), 국내("005930", "삼성전자")])
        with 세션() as s:
            application.snapshot(s, Region.KR, 분(10, 16), TAKEN)

        with 세션() as s:
            tick = application.latest(s, Region.KR, 그날)

        assert tick.at == 분(10, 16)
        assert [(x.rank, x.name) for x in tick.stocks] == [(1, "SK하이닉스"), (2, "삼성전자")]

    def test_찍힌_분이_없으면_없다(self, 빈_타임라인):
        with 세션() as s:
            assert application.latest(s, Region.KR, 그날) is None
