"""주도주 폴러 — 쿨다운, 일자 전환, 반등 무장/재무장.

폴러는 상태를 메모리에 들고 돈다. 이 상태 관리가 틀리면 같은 시그널이 반복 적재되거나
반대로 영영 안 울린다 — 둘 다 조용히 잘못되는 종류다.
"""

from datetime import date, datetime, timedelta

import pytest

from backend.leadingstock import application, events, scheduler
from backend.leadingstock.application import CandidateSignalReading
from backend.leadingstock.entities import (
    MarketFlowStateSnapshot,
    MarketSignalEvent,
    SignalEvent,
)
from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.platform.kiwoom.sector_investor import SectorInvestorNetBuy
from backend.stock.domain import Market
from backend.leadingstock.signals import SignalEventType
from backend.library.db import get_engine, get_session_factory

AT = datetime(2026, 9, 11, 10, 0)
오늘 = date(2026, 9, 11)


def 측정(종목="005930", 스파이크=None) -> CandidateSignalReading:
    return CandidateSignalReading(
        stock_code=종목, stock_name="삼성전자", current_price=70_000, price_change_rate=5.0,
        trading_value=1_000_000_000, gap_rate=None, peak_price=None, spike_ratio=스파이크,
        minute_trading_value=None, spike_direction=None,
    )


@pytest.fixture
def 폴러_초기화(통합_db):
    SignalEvent.__table__.create(get_engine(), checkfirst=True)
    MarketSignalEvent.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as s:
        s.query(SignalEvent).delete()
        s.query(MarketSignalEvent).delete()
        s.commit()
    scheduler._signal_states.clear()
    scheduler._last_fired.clear()
    scheduler._signal_trade_date = None
    scheduler._rebound_armed.clear()
    scheduler._breakdown_armed.clear()
    yield
    scheduler._signal_states.clear()
    scheduler._last_fired.clear()


def 적재된_이벤트() -> list[SignalEvent]:
    with get_session_factory()() as s:
        return list(s.query(SignalEvent).all())


class Test종목_시그널_폴러:
    def test_전이가_일어나면_이벤트를_적재한다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(스파이크=5.0)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)

        scheduler._detect_signal_events()

        적재 = 적재된_이벤트()
        assert len(적재) == 1
        assert 적재[0].event_type == SignalEventType.VOLUME_SPIKE.value

    def test_전이가_없으면_아무것도_적재하지_않는다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정()])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)

        scheduler._detect_signal_events()

        assert 적재된_이벤트() == []

    def test_쿨다운_안에_같은_전이가_또_뜨면_건너뛴다(self, 폴러_초기화, monkeypatch):
        """프리마켓 출렁임으로 같은 전이가 반복 적재되는 걸 막는다."""
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(스파이크=5.0)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        scheduler._detect_signal_events()

        # 상태를 리셋해 전이가 다시 잡히게 만들되, 쿨다운 안의 시각으로 돈다
        scheduler._signal_states.clear()
        monkeypatch.setattr(scheduler, "now", lambda: AT + timedelta(minutes=1))
        scheduler._detect_signal_events()

        assert len(적재된_이벤트()) == 1

    def test_쿨다운이_지나면_다시_적재한다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(스파이크=5.0)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        scheduler._detect_signal_events()

        scheduler._signal_states.clear()
        monkeypatch.setattr(scheduler, "now", lambda: AT + timedelta(minutes=5))
        scheduler._detect_signal_events()

        assert len(적재된_이벤트()) == 2

    def test_일자가_바뀌면_직전_상태를_버린다(self, 폴러_초기화, monkeypatch):
        """어제 돌파 상태를 들고 있으면 오늘 첫 돌파를 놓친다."""
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(스파이크=5.0)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        scheduler._detect_signal_events()

        내일 = 오늘 + timedelta(days=1)
        monkeypatch.setattr(scheduler, "today", lambda: 내일)
        monkeypatch.setattr(scheduler, "now", lambda: AT + timedelta(days=1))
        scheduler._detect_signal_events()

        assert len(적재된_이벤트()) == 2  # 새 날 첫 돌파가 다시 잡힌다

    def test_스파이크_행에는_배율이_남고_이평은_비어_있다(self, 폴러_초기화, monkeypatch):
        """이평 칸은 반등·꺾임이 쓰던 자리다 — 생성이 멈춘 뒤로는 비어 있어야 한다."""
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(스파이크=5.0)])

        scheduler._detect_signal_events()

        [스파이크] = 적재된_이벤트()
        assert (스파이크.spike_ratio, 스파이크.ma) == (5.0, None)

    def test_휴장이면_폴러가_돌지_않는다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (True, True))
        호출됨 = []
        monkeypatch.setattr(scheduler, "_detect_signal_events", lambda: 호출됨.append(1))

        scheduler.poll_signal_events()

        assert 호출됨 == []

    def test_장_시간이_아니면_돌지_않는다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, False))
        호출됨 = []
        monkeypatch.setattr(scheduler, "_detect_signal_events", lambda: 호출됨.append(1))

        scheduler.poll_signal_events()

        assert 호출됨 == []


def 순매수(지수=2500.0, 등락률=1.5):
    """키움 업종 투자자 순매수 응답 한 시장치."""
    return SectorInvestorNetBuy(
        foreign_eok=100, institution_eok=50, individual_eok=-150, other_corp_eok=0,
        financial_investment_eok=10, trust_eok=10, pension_fund_eok=10,
        private_equity_eok=10, insurance_eok=5, bank_eok=5, other_finance_eok=0,
        index_value=지수, change_rate=등락률,
    )


@pytest.fixture
def 수급_폴러_초기화(통합_db):
    MarketInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)
    MarketSignalEvent.__table__.create(get_engine(), checkfirst=True)
    MarketFlowStateSnapshot.__table__.create(get_engine(), checkfirst=True)
    with get_session_factory()() as s:
        s.query(MarketInvestorSnapshot).delete()
        s.query(MarketSignalEvent).delete()
        s.query(MarketFlowStateSnapshot).delete()
        s.commit()
    scheduler._market_trade_date = None
    scheduler._level_states.clear()
    scheduler._flow_states.clear()
    yield
    scheduler._level_states.clear()
    scheduler._flow_states.clear()


@pytest.mark.integration
class Test수급_스냅샷:
    def test_지수_레벨과_등락률을_함께_남긴다(self, 수급_폴러_초기화, monkeypatch):
        """운영 테이블의 두 컬럼은 NOT NULL이다 — 빼고 적재하면 수급이 통째로 안 쌓인다."""
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(
            events, "investor_net_buy", lambda: {Market.KOSPI: 순매수(지수=2500.0, 등락률=1.5)}
        )

        scheduler._detect_market_signals()

        with get_session_factory()() as s:
            적재 = s.query(MarketInvestorSnapshot).all()
            assert len(적재) == 1
            assert 적재[0].index_value == 2500.0
            assert 적재[0].change_rate == 1.5


class Test거래대금_상위_갱신:
    def test_장중이면_거래대금_상위를_갈아_끼운다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))
        호출됨 = []
        monkeypatch.setattr(application, "refresh_trading_value_pool", lambda: 호출됨.append(1))

        scheduler.refresh_trading_value_pool()

        assert 호출됨 == [1]

    def test_휴장이면_갱신하지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (True, True))
        호출됨 = []
        monkeypatch.setattr(application, "refresh_trading_value_pool", lambda: 호출됨.append(1))

        scheduler.refresh_trading_value_pool()

        assert 호출됨 == []

    def test_장_시간이_아니면_갱신하지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, False))
        호출됨 = []
        monkeypatch.setattr(application, "refresh_trading_value_pool", lambda: 호출됨.append(1))

        scheduler.refresh_trading_value_pool()

        assert 호출됨 == []

    def test_실패해도_폴러가_죽지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))

        def 터진다():
            raise RuntimeError("키움 오류")

        monkeypatch.setattr(application, "refresh_trading_value_pool", 터진다)

        scheduler.refresh_trading_value_pool()


class Test당일_분봉_갱신:
    def test_장중이면_감시_풀의_분봉을_이어_받는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))
        호출됨 = []
        monkeypatch.setattr(application, "sync_today_minutes", lambda: 호출됨.append(1) or 0)

        scheduler.sync_today_minutes()

        assert 호출됨 == [1]

    @pytest.mark.parametrize("휴장, 거래시간", [(True, True), (False, False)])
    def test_휴장이거나_장_시간이_아니면_받지_않는다(self, monkeypatch, 휴장, 거래시간):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (휴장, 거래시간))
        호출됨 = []
        monkeypatch.setattr(application, "sync_today_minutes", lambda: 호출됨.append(1) or 0)

        scheduler.sync_today_minutes()

        assert 호출됨 == []

    def test_일부_종목이_실패하면_실패로_센다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))
        monkeypatch.setattr(application, "sync_today_minutes", lambda: 2)
        실패 = []
        monkeypatch.setattr(scheduler.metrics, "job_failed", 실패.append)

        scheduler.sync_today_minutes()

        assert 실패 == [scheduler._MINUTE_SYNC_JOB]

    def test_통째로_실패해도_폴러가_죽지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))

        def 터진다():
            raise RuntimeError("후보 조회 실패")

        monkeypatch.setattr(application, "sync_today_minutes", 터진다)

        scheduler.sync_today_minutes()


class Test당일_분봉_마감_확정:
    def test_평일이면_확정한다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, False))
        호출됨 = []
        monkeypatch.setattr(application, "settle_today_minutes", lambda: 호출됨.append(1) or 0)

        scheduler.settle_today_minutes()

        assert 호출됨 == [1]

    def test_휴장이면_하지_않는다(self, monkeypatch):
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (True, False))
        호출됨 = []
        monkeypatch.setattr(application, "settle_today_minutes", lambda: 호출됨.append(1) or 0)

        scheduler.settle_today_minutes()

        assert 호출됨 == []
