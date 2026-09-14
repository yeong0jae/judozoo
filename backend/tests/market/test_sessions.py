"""세션별 수급 — 당일 누적 스냅샷의 경계 diff.

M4에서 가장 깨지기 쉬운 곳이다. 값을 직접 주는 API가 없어 스냅샷끼리 빼서 만들기 때문에,
경계 시각이나 폴백 규칙이 어긋나면 **숫자가 통째로 틀린 채 그럴듯하게 보인다**.
"""

from datetime import date, datetime

import pytest

from backend.leadingstock.infrastructure import MarketInvestorSnapshot
from backend.library.db import get_engine, get_session_factory
from backend.market import application
from backend.market.domain import ProgramTradeSnapshot
from backend.stock.domain import Market

당일 = date(2026, 9, 11)
AT = datetime(2026, 9, 11, 8, 0)


def 수급스냅샷(hh: int, mm: int, 개인: int, 외국인: int, 기관: int) -> MarketInvestorSnapshot:
    return MarketInvestorSnapshot(
        market=Market.KOSPI, trade_date=당일, captured_at=datetime(2026, 9, 11, hh, mm),
        individual_eok=개인, foreign_eok=외국인, institution_eok=기관, other_corp_eok=0,
        financial_investment_eok=0, trust_eok=0, pension_fund_eok=0, private_equity_eok=0,
        insurance_eok=0, bank_eok=0, other_finance_eok=0,
        index_value=2500.0, change_rate=1.0, created_at=AT, updated_at=AT,
    )


def 프로그램스냅샷(hh: int, mm: int, 차익: int, 비차익: int, 전체: int) -> ProgramTradeSnapshot:
    return ProgramTradeSnapshot(
        market=Market.KOSPI, trade_date=당일, captured_at=datetime(2026, 9, 11, hh, mm),
        arbitrage_mil=차익, non_arbitrage_mil=비차익, total_mil=전체,
        created_at=AT, updated_at=AT,
    )


@pytest.fixture
def 빈_스냅샷_테이블(통합_db):
    engine = get_engine()
    for model in (MarketInvestorSnapshot, ProgramTradeSnapshot):
        model.__table__.create(engine, checkfirst=True)
    with get_session_factory()() as session:
        session.query(MarketInvestorSnapshot).delete()
        session.query(ProgramTradeSnapshot).delete()
        session.commit()
    yield


@pytest.fixture
def 휴장판정_없음(monkeypatch):
    """KIS를 두드리지 않게 — 판정 불가(None)로 고정한다."""
    monkeypatch.setattr("backend.market.calendar.is_open", lambda _d: None)


class Test투자자_세션_수급:
    def test_경계_스냅샷의_차이로_세션_순매수를_만든다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            s.add_all([
                수급스냅샷(9, 0, 개인=100, 외국인=-50, 기관=-50),    # 프리 누적
                수급스냅샷(12, 0, 개인=300, 외국인=-150, 기관=-150),  # 오전까지 누적
                수급스냅샷(15, 0, 개인=500, 외국인=-200, 기관=-300),  # 오후까지 누적
            ])
            s.commit()

            _, 세션들 = application.investor_sessions(s, Market.KOSPI, 당일)

        이름별 = {x.name: x.nets for x in 세션들}
        assert 이름별["프리마켓"].individual == 100          # 누적 그대로
        assert 이름별["오전"].individual == 200              # 300 − 100
        assert 이름별["오후"].individual == 200              # 500 − 300
        assert 이름별["오전"].foreign == -100                # -150 − (-50)

    def test_구간_합이_그날_최종_누적과_같다(self, 빈_스냅샷_테이블):
        """이 성질이 깨지면 일별 표와 세션 표의 숫자가 어긋난다."""
        with get_session_factory()() as s:
            s.add_all([
                수급스냅샷(9, 0, 개인=100, 외국인=-50, 기관=-50),
                수급스냅샷(12, 0, 개인=300, 외국인=-150, 기관=-150),
                수급스냅샷(15, 0, 개인=500, 외국인=-200, 기관=-300),
                수급스냅샷(15, 40, 개인=600, 외국인=-250, 기관=-350),
                수급스냅샷(20, 0, 개인=700, 외국인=-300, 기관=-400),
            ])
            s.commit()
            _, 세션들 = application.investor_sessions(s, Market.KOSPI, 당일)

        합 = sum(x.nets.individual for x in 세션들 if x.nets)
        assert 합 == 700  # 최종 누적

    def test_프리_스냅샷이_없으면_오전이_프리를_포함해_합을_보존한다(self, 빈_스냅샷_테이블):
        """폴러가 프리 스냅샷을 남기기 전 과거 데이터 호환."""
        with get_session_factory()() as s:
            s.add_all([
                수급스냅샷(12, 0, 개인=300, 외국인=-150, 기관=-150),
                수급스냅샷(15, 0, 개인=500, 외국인=-200, 기관=-300),
            ])
            s.commit()
            _, 세션들 = application.investor_sessions(s, Market.KOSPI, 당일)

        이름별 = {x.name: x.nets for x in 세션들}
        assert 이름별["프리마켓"] is None
        assert 이름별["오전"].individual == 300  # 프리 포함 누적 그대로
        assert sum(x.nets.individual for x in 세션들 if x.nets) == 500

    def test_스냅샷이_없는_세션은_None이다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            _, 세션들 = application.investor_sessions(s, Market.KOSPI, 당일)

        assert [x.nets for x in 세션들] == [None] * 5
        assert [x.name for x in 세션들] == ["프리마켓", "오전", "오후", "마감 구간", "애프터마켓"]

    def test_경계_시각_이하의_가장_가까운_스냅샷을_쓴다(self, 빈_스냅샷_테이블):
        """폴러는 60~120초 간격이라 정확히 12:00인 스냅샷은 거의 없다."""
        with get_session_factory()() as s:
            s.add_all([
                수급스냅샷(9, 0, 개인=100, 외국인=0, 기관=0),
                수급스냅샷(11, 58, 개인=290, 외국인=0, 기관=0),   # 경계 직전 — 이게 쓰여야 한다
                수급스냅샷(12, 2, 개인=310, 외국인=0, 기관=0),    # 경계 직후 — 쓰이면 안 된다
            ])
            s.commit()
            _, 세션들 = application.investor_sessions(s, Market.KOSPI, 당일)

        assert {x.name: x.nets for x in 세션들}["오전"].individual == 190  # 290 − 100

    def test_다른_날짜_스냅샷은_섞이지_않는다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            어제 = MarketInvestorSnapshot(
                market=Market.KOSPI, trade_date=date(2026, 9, 10),
                captured_at=datetime(2026, 9, 10, 15, 0),
                individual_eok=9999, foreign_eok=0, institution_eok=0, other_corp_eok=0,
                financial_investment_eok=0, trust_eok=0, pension_fund_eok=0,
                private_equity_eok=0, insurance_eok=0, bank_eok=0, other_finance_eok=0,
                index_value=2500.0, change_rate=1.0,
                created_at=AT, updated_at=AT,
            )
            s.add_all([어제, 수급스냅샷(12, 0, 개인=300, 외국인=0, 기관=0)])
            s.commit()
            _, 세션들 = application.investor_sessions(s, Market.KOSPI, 당일)

        assert {x.name: x.nets for x in 세션들}["오전"].individual == 300


class Test프로그램_세션_수급:
    def test_백만원을_억원으로_바꿔_차이를_낸다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            s.add_all([
                프로그램스냅샷(9, 0, 차익=10_000, 비차익=20_000, 전체=30_000),
                프로그램스냅샷(12, 0, 차익=30_000, 비차익=50_000, 전체=80_000),
            ])
            s.commit()
            _, 세션들 = application.program_sessions(s, Market.KOSPI, 당일)

        이름별 = {x.name: x.nets for x in 세션들}
        assert 이름별["프리마켓"].total_eok == 300     # 30,000백만 = 300억
        assert 이름별["오전"].total_eok == 500         # 800억 − 300억

    def test_스냅샷이_없으면_None(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            assert [x.nets for x in application.program_sessions(s, Market.KOSPI, 당일)[1]] == [None] * 5


class Test선물_세션_수급:
    def test_오전은_누적_그대로_이후는_차이로_낸다(self, 빈_스냅샷_테이블, 휴장판정_없음):
        from backend.market.domain import FuturesInvestorSnapshot

        FuturesInvestorSnapshot.__table__.create(get_engine(), checkfirst=True)

        def 선물스냅샷(hh, mm, 외국인):
            return FuturesInvestorSnapshot(
                market=Market.KOSPI, trade_date=당일, captured_at=datetime(2026, 9, 11, hh, mm),
                foreign_qty=외국인, institution_qty=0, individual_qty=0,
                securities_qty=0, insurance_qty=0, merchant_bank_qty=0, trust_qty=0,
                private_equity_qty=0, fund_qty=0, bank_qty=0, other_org_qty=0, other_corp_qty=0,
                created_at=AT, updated_at=AT,
            )

        with get_session_factory()() as s:
            s.query(FuturesInvestorSnapshot).delete()
            s.add_all([선물스냅샷(12, 0, 1000), 선물스냅샷(15, 0, 1500)])
            s.commit()
            _, 세션들 = application.futures_investor_sessions(s, Market.KOSPI, 당일)

        이름별 = {x.name: x.nets for x in 세션들}
        assert 이름별["오전"].foreign == 1000   # 누적 그대로
        assert 이름별["오후"].foreign == 500    # 1500 − 1000
        assert [x.name for x in 세션들] == ["오전", "오후", "마감 구간"]


class Test데이터_있는_날로_물러나기:
    """공휴일·주말엔 그날 스냅샷이 없다. 빈 화면 대신 직전 거래일을 보여주되,
    **실제로 쓴 날짜를 함께 돌려줘야** 화면이 거짓 라벨을 붙이지 않는다.
    """

    def test_요청일에_데이터가_없으면_직전_거래일로_물러난다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            s.add_all([수급스냅샷(9, 0, 10, 20, 30), 수급스냅샷(12, 0, 40, 50, 60)])
            s.commit()

            휴장일 = date(2026, 9, 14)  # 스냅샷이 없는 날
            쓴날짜, 세션들 = application.investor_sessions(s, Market.KOSPI, 휴장일)

        assert 쓴날짜 == 당일
        assert any(x.nets is not None for x in 세션들)

    def test_요청일에_데이터가_있으면_그날을_그대로_쓴다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            s.add(수급스냅샷(9, 0, 10, 20, 30))
            s.commit()

            쓴날짜, _ = application.investor_sessions(s, Market.KOSPI, 당일)

        assert 쓴날짜 == 당일

    def test_이전_데이터가_아예_없으면_요청일을_그대로_돌려준다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            아주_과거 = date(2020, 1, 2)
            쓴날짜, 세션들 = application.investor_sessions(s, Market.KOSPI, 아주_과거)

        assert 쓴날짜 == 아주_과거
        assert all(x.nets is None for x in 세션들)

    def test_미래_데이터로는_물러나지_않는다(self, 빈_스냅샷_테이블):
        """`on` 이하만 본다 — 요청일보다 뒤의 데이터를 끌어오면 안 된다."""
        with get_session_factory()() as s:
            s.add(수급스냅샷(9, 0, 10, 20, 30))  # 09-11
            s.commit()

            이전날 = date(2026, 9, 10)
            쓴날짜, _ = application.investor_sessions(s, Market.KOSPI, 이전날)

        assert 쓴날짜 == 이전날

    def test_프로그램도_같은_규칙으로_물러난다(self, 빈_스냅샷_테이블):
        with get_session_factory()() as s:
            s.add_all([프로그램스냅샷(9, 0, 1, 2, 3), 프로그램스냅샷(12, 0, 4, 5, 6)])
            s.commit()

            쓴날짜, _ = application.program_sessions(s, Market.KOSPI, date(2026, 9, 14))

        assert 쓴날짜 == 당일
