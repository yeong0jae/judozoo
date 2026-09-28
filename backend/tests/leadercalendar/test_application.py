"""주도주 캘린더 — 마감 스냅샷이 남기는 것과 월 조회."""

from datetime import date, datetime

import pytest

from backend.leadercalendar import application
from backend.leadercalendar.entities import LeaderDay, LeaderDayStock
from backend.leadertimeline import application as leadertimeline
from backend.leadertimeline.entities import LeaderTick, LeaderTickStock
from backend.leadingstock.domain import LeadingStockSnapshot
from backend.library import db
from backend.market.calendar import Region
from backend.overseasleadingstock.domain import OverseasStockRank

AT = datetime(2026, 9, 23, 20, 1)
그날 = date(2026, 9, 23)


def 국내(code: str, name: str, 등락률: float = 5.0) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=code, stock_name=name, current_price=70_000, price_change_rate=등락률,
        trading_value_rank=1, accumulated_trading_value=1_234_500_000_000,
    )


def 해외(symbol: str, name: str) -> OverseasStockRank:
    return OverseasStockRank(
        rank=1, exchange="NAS", symbol=symbol, name=name, ename=symbol,
        price=182.37, diff=3.0, rate=2.6, trading_value=44_300_000_000.5,
    )


@pytest.fixture
def 빈_캘린더(통합_db):
    engine = db.get_engine()
    LeaderDay.__table__.create(engine, checkfirst=True)
    LeaderDayStock.__table__.create(engine, checkfirst=True)
    # 월 조회가 마감 기록 전의 오늘을 타임라인에서 채운다
    LeaderTick.__table__.create(engine, checkfirst=True)
    LeaderTickStock.__table__.create(engine, checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(LeaderDayStock).delete()
        s.query(LeaderDay).delete()
        s.query(LeaderTickStock).delete()
        s.query(LeaderTick).delete()
        s.commit()


def 세션():
    return db.get_session_factory()()


def 국내_주도주(mocker, stocks):
    return mocker.patch.object(application.leadingstock, "find_leaders", return_value=stocks)


@pytest.mark.integration
class Test국내_마감_스냅샷:
    def test_홈_주도주를_순서대로_남긴다(self, 빈_캘린더, mocker):
        국내_주도주(mocker, [국내("000660", "SK하이닉스"), 국내("005930", "삼성전자")])

        with 세션() as s:
            application.snapshot_domestic(s, 그날, AT)
        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert [d.trade_date for d in 국내들] == [그날]
        assert [(x.rank, x.name) for x in 국내들[0].stocks] == [(1, "SK하이닉스"), (2, "삼성전자")]

    def test_거래소_접미사를_떼고_종목코드만_남긴다(self, 빈_캘린더, mocker):
        국내_주도주(mocker, [국내("009150_AL", "삼성전기")])

        with 세션() as s:
            application.snapshot_domestic(s, 그날, AT)
        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert 국내들[0].stocks[0].code == "009150"

    def test_주도주가_없는_날도_날은_남는다(self, 빈_캘린더, mocker):
        국내_주도주(mocker, [])

        with 세션() as s:
            application.snapshot_domestic(s, 그날, AT)
        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert [(d.trade_date, d.stocks) for d in 국내들] == [(그날, [])]

    def test_같은_날을_다시_찍으면_한_벌만_남는다(self, 빈_캘린더, mocker):
        국내_주도주(mocker, [국내("000660", "SK하이닉스"), 국내("005930", "삼성전자")])
        with 세션() as s:
            application.snapshot_domestic(s, 그날, AT)

        국내_주도주(mocker, [국내("042700", "한미반도체")])
        with 세션() as s:
            application.snapshot_domestic(s, 그날, datetime(2026, 9, 23, 20, 30))
        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert len(국내들) == 1
        assert [x.name for x in 국내들[0].stocks] == ["한미반도체"]

    def test_휴장일은_종목_없이_휴장으로_남는다(self, 빈_캘린더):
        with 세션() as s:
            application.record_closed(s, Region.KR, date(2026, 9, 24), AT)
        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert [(d.trade_date, d.stocks, d.closed) for d in 국내들] == [(date(2026, 9, 24), [], True)]

    def test_주도주를_못_받으면_아무것도_남기지_않는다(self, 빈_캘린더, mocker):
        """0개로 남기면 브로커 오류가 "주도주 없음"으로 굳는다."""
        mocker.patch.object(application.leadingstock, "find_leaders", side_effect=RuntimeError("키움 오류"))

        with 세션() as s, pytest.raises(RuntimeError):
            application.snapshot_domestic(s, 그날, AT)
        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert 국내들 == []


@pytest.mark.integration
class Test해외_마감_스냅샷:
    def test_거래소와_달러_값을_그대로_남긴다(self, 빈_캘린더, mocker):
        mocker.patch.object(application.overseasleadingstock, "get_leaders", return_value=[해외("NVDA", "엔비디아")])

        with 세션() as s:
            application.snapshot_overseas(s, date(2026, 9, 22), AT)
        with 세션() as s:
            _, 해외들 = application.find_month(s, 2026, 9)

        종목 = 해외들[0].stocks[0]
        assert (종목.exchange, 종목.code, 종목.name) == ("NAS", "NVDA", "엔비디아")
        assert float(종목.price) == 182.37
        assert float(종목.trading_value) == 44_300_000_000.5


@pytest.mark.integration
class Test월_조회:
    def test_해외는_그달_첫날_직전_평일부터_온다(self, 빈_캘린더, mocker):
        """9/1 칸에는 8/31 해외장이 붙는다. 국내 8/31은 9월 달력에 필요 없다."""
        mocker.patch.object(application.leadingstock, "find_leaders", return_value=[국내("000660", "SK하이닉스")])
        mocker.patch.object(application.overseasleadingstock, "get_leaders", return_value=[해외("TSLA", "테슬라")])
        with 세션() as s:
            for d in (date(2026, 8, 28), date(2026, 8, 31), date(2026, 9, 1), date(2026, 10, 1)):
                application.snapshot_domestic(s, d, AT)
                application.snapshot_overseas(s, d, AT)

        with 세션() as s:
            국내들, 해외들 = application.find_month(s, 2026, 9)

        assert [d.trade_date for d in 국내들] == [date(2026, 9, 1)]
        assert [d.trade_date for d in 해외들] == [date(2026, 8, 31), date(2026, 9, 1)]


def 지금은(mocker, kst: datetime):
    mocker.patch("backend.market.calendar.now", return_value=kst)


def 타임라인에_찍는다(mocker, 분: datetime, stocks):
    국내_주도주(mocker, stocks)
    with 세션() as s:
        leadertimeline.snapshot(s, Region.KR, 분, 분)


@pytest.mark.integration
class Test장중_오늘:
    def test_마감_기록_전에는_타임라인의_마지막_분으로_채우고_진행_중으로_표시한다(self, 빈_캘린더, mocker):
        타임라인에_찍는다(mocker, datetime(2026, 9, 23, 10, 0), [국내("005930", "삼성전자")])
        타임라인에_찍는다(mocker, datetime(2026, 9, 23, 10, 1), [국내("000660", "SK하이닉스"), 국내("005930", "삼성전자")])
        지금은(mocker, datetime(2026, 9, 23, 10, 1, 30))

        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert [(d.trade_date, d.live) for d in 국내들] == [(그날, True)]
        assert [(x.rank, x.name) for x in 국내들[0].stocks] == [(1, "SK하이닉스"), (2, "삼성전자")]

    def test_마감_기록이_생기면_그것을_준다(self, 빈_캘린더, mocker):
        타임라인에_찍는다(mocker, datetime(2026, 9, 23, 20, 0), [국내("005930", "삼성전자")])
        국내_주도주(mocker, [국내("042700", "한미반도체")])
        with 세션() as s:
            application.snapshot_domestic(s, 그날, AT)
        지금은(mocker, datetime(2026, 9, 23, 20, 5))

        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert [(d.trade_date, d.live) for d in 국내들] == [(그날, False)]
        assert [x.name for x in 국내들[0].stocks] == ["한미반도체"]

    def test_오늘_찍힌_분이_없으면_비워_둔다(self, 빈_캘린더, mocker):
        """장 전에는 풀이 어제 값을 들고 있다 — 타임라인이 찍지 않았으니 오늘 칸에 들어오지 않는다."""
        타임라인에_찍는다(mocker, datetime(2026, 9, 22, 20, 0), [국내("005930", "삼성전자")])
        지금은(mocker, datetime(2026, 9, 23, 7, 30))

        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 9)

        assert 국내들 == []

    def test_다른_달을_보면_오늘을_채우지_않는다(self, 빈_캘린더, mocker):
        타임라인에_찍는다(mocker, datetime(2026, 9, 23, 10, 0), [국내("005930", "삼성전자")])
        지금은(mocker, datetime(2026, 9, 23, 10, 0, 30))

        with 세션() as s:
            국내들, _ = application.find_month(s, 2026, 8)

        assert 국내들 == []
