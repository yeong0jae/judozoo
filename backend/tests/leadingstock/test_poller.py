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


def 측정(
    종목="005930", 스파이크=None, 이평상향=None, 이평하향=None, 이평=None,
) -> CandidateSignalReading:
    return CandidateSignalReading(
        stock_code=종목, stock_name="삼성전자", current_price=70_000, price_change_rate=5.0,
        trading_value=1_000_000_000, gap_rate=None, peak_price=None, spike_ratio=스파이크,
        minute_trading_value=None, spike_direction=None,
        ma_crossed_up=이평상향, ma_crossed_down=이평하향,
        ma_below_band=None, ma_above_band=None, ma=이평,
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
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(이평상향=True)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)

        scheduler._detect_signal_events()

        적재 = 적재된_이벤트()
        assert len(적재) == 1
        assert 적재[0].event_type == SignalEventType.MA_REBOUND.value

    def test_전이가_없으면_아무것도_적재하지_않는다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정()])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)

        scheduler._detect_signal_events()

        assert 적재된_이벤트() == []

    def test_쿨다운_안에_같은_전이가_또_뜨면_건너뛴다(self, 폴러_초기화, monkeypatch):
        """프리마켓 출렁임으로 같은 전이가 반복 적재되는 걸 막는다."""
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(이평상향=True)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        scheduler._detect_signal_events()

        # 상태를 리셋해 전이가 다시 잡히게 만들되, 쿨다운 안의 시각으로 돈다
        scheduler._signal_states.clear()
        monkeypatch.setattr(scheduler, "now", lambda: AT + timedelta(minutes=1))
        scheduler._detect_signal_events()

        assert len(적재된_이벤트()) == 1

    def test_쿨다운이_지나면_다시_적재한다(self, 폴러_초기화, monkeypatch):
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(이평상향=True)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        scheduler._detect_signal_events()

        scheduler._signal_states.clear()
        monkeypatch.setattr(scheduler, "now", lambda: AT + timedelta(minutes=5))
        scheduler._detect_signal_events()

        assert len(적재된_이벤트()) == 2

    def test_일자가_바뀌면_직전_상태를_버린다(self, 폴러_초기화, monkeypatch):
        """어제 돌파 상태를 들고 있으면 오늘 첫 돌파를 놓친다."""
        monkeypatch.setattr(application, "signal_readings", lambda _r: [측정(이평상향=True)])
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        scheduler._detect_signal_events()

        내일 = 오늘 + timedelta(days=1)
        monkeypatch.setattr(scheduler, "today", lambda: 내일)
        monkeypatch.setattr(scheduler, "now", lambda: AT + timedelta(days=1))
        scheduler._detect_signal_events()

        assert len(적재된_이벤트()) == 2  # 새 날 첫 돌파가 다시 잡힌다

    def test_타입별로_쿨다운이_따로_걸린다(self, 폴러_초기화, monkeypatch):
        """반등과 스파이크는 서로의 쿨다운에 막히지 않아야 한다."""
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(
            application, "signal_readings", lambda _r: [측정(스파이크=5.0, 이평상향=True)]
        )

        scheduler._detect_signal_events()

        타입들 = {e.event_type for e in 적재된_이벤트()}
        assert 타입들 == {SignalEventType.MA_REBOUND.value, SignalEventType.VOLUME_SPIKE.value}

    def test_이벤트마다_해당_필드만_채워진다(self, 폴러_초기화, monkeypatch):
        """스파이크 배율은 스파이크 행에만, 이평값은 반등 행에만 들어간다."""
        monkeypatch.setattr(scheduler, "today", lambda: 오늘)
        monkeypatch.setattr(scheduler, "now", lambda: AT)
        monkeypatch.setattr(
            application, "signal_readings", lambda _r: [측정(스파이크=5.0, 이평상향=True, 이평=1000)]
        )

        scheduler._detect_signal_events()

        이벤트별 = {e.event_type: e for e in 적재된_이벤트()}
        assert 이벤트별[SignalEventType.VOLUME_SPIKE.value].spike_ratio == 5.0
        assert 이벤트별[SignalEventType.VOLUME_SPIKE.value].ma is None
        assert 이벤트별[SignalEventType.MA_REBOUND.value].ma == 1000
        assert 이벤트별[SignalEventType.MA_REBOUND.value].spike_ratio is None

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
