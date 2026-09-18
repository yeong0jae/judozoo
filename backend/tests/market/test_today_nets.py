"""첫 화면 "오늘의 수급" — 현물 두 시장과 지수선물 두 시장을 한 목록에 모은다.

현물은 억원, 선물은 계약이다. 단위가 다른 값이 한 목록에 담기므로 **어느 칸이 어느 단위인지**
(`futures`)를 잃지 않는 것이 여기서 제일 중요하다.
"""

import pytest

from backend.market import application
from backend.platform.kis.futures import FuturesInvestors
from backend.platform.kiwoom.sector_investor import SectorInvestorNetBuy
from backend.stock.domain import Market


def 현물수급(*, 개인, 외인, 기관, 기타법인, 지수=2653.81, 등락=1.24) -> SectorInvestorNetBuy:
    return SectorInvestorNetBuy(
        foreign_eok=외인,
        institution_eok=기관,
        individual_eok=개인,
        other_corp_eok=기타법인,
        financial_investment_eok=0,
        trust_eok=0,
        pension_fund_eok=0,
        private_equity_eok=0,
        insurance_eok=0,
        bank_eok=0,
        other_finance_eok=0,
        index_value=지수,
        change_rate=등락,
    )


def 선물시세(*, 개인, 외인, 기관, 기타법인, 가격=943.20, 등락=2.71):
    투자자 = FuturesInvestors(
        foreign=외인,
        individual=개인,
        institution=기관,
        securities=0,
        insurance=0,
        merchant_bank=0,
        trust=0,
        private_equity=0,
        fund=0,
        bank=0,
        other_org=0,
        other_corp=기타법인,
    )
    return application.FuturesQuote(
        futures_price=가격,
        change_rate=등락,
        spot=0.0,
        basis=0.0,
        dprt=0.0,
        open_interest=0,
        open_interest_change=0,
        rmnn_days=0,
        expiry_date="2026-09-10",
        investors=application.FuturesInvestorsSummary(
            투자자.foreign, 투자자.individual, 투자자.institution, 투자자.other_corp
        ),
    )


class Test오늘의_수급:
    def test_현물_두_시장과_선물_두_시장이_순서대로_담긴다(self, mocker):
        mocker.patch.object(
            application.kiwoom_sector,
            "fetch_sector_net_buy",
            side_effect=lambda mrkt_tp, base_dt=None: 현물수급(
                개인=-8420, 외인=6150, 기관=2180, 기타법인=90
            ),
        )
        mocker.patch.object(
            application,
            "futures_quote",
            side_effect=lambda market: 선물시세(개인=-4210, 외인=12480, 기관=-7980, 기타법인=-290),
        )

        결과 = application.today_nets()

        assert [(n.market, n.futures) for n in 결과] == [
            (Market.KOSPI, False),
            (Market.KOSDAQ, False),
            (Market.KOSPI, True),
            (Market.KOSDAQ, True),
        ]

    def test_현물은_억원_선물은_계약을_그대로_싣는다(self, mocker):
        mocker.patch.object(
            application.kiwoom_sector,
            "fetch_sector_net_buy",
            return_value=현물수급(개인=-8420, 외인=6150, 기관=2180, 기타법인=90),
        )
        mocker.patch.object(
            application,
            "futures_quote",
            return_value=선물시세(개인=-4210, 외인=12480, 기관=-7980, 기타법인=-290),
        )

        현물, 선물 = application.today_nets()[0], application.today_nets()[2]

        assert (현물.nets.individual, 현물.nets.foreign, 현물.nets.institution,
                현물.nets.other_corp) == (-8420, 6150, 2180, 90)
        assert (선물.nets.individual, 선물.nets.foreign, 선물.nets.institution,
                선물.nets.other_corp) == (-4210, 12480, -7980, -290)

    def test_지수값과_등락률은_현물은_지수_선물은_선물가를_쓴다(self, mocker):
        mocker.patch.object(
            application.kiwoom_sector,
            "fetch_sector_net_buy",
            return_value=현물수급(개인=0, 외인=0, 기관=0, 기타법인=0, 지수=2653.81, 등락=1.24),
        )
        mocker.patch.object(
            application,
            "futures_quote",
            return_value=선물시세(개인=0, 외인=0, 기관=0, 기타법인=0, 가격=943.20, 등락=2.71),
        )

        결과 = application.today_nets()

        assert (결과[0].index_value, 결과[0].change_rate) == (2653.81, 1.24)
        assert (결과[2].index_value, 결과[2].change_rate) == (943.20, 2.71)

    def test_선물_시세조차_안_오면_그_칸은_빠진다(self, mocker):
        """수급만 빈 것과 다르다 — 지수값도 없으면 보여줄 게 없다."""
        mocker.patch.object(
            application.kiwoom_sector,
            "fetch_sector_net_buy",
            return_value=현물수급(개인=-1, 외인=1, 기관=0, 기타법인=0),
        )
        mocker.patch.object(application, "futures_quote", return_value=None)

        assert all(n.futures is False for n in application.today_nets())

    def test_현물이_안_오면_그_시장은_빠지고_선물만_남는다(self, mocker):
        mocker.patch.object(application.kiwoom_sector, "fetch_sector_net_buy", return_value=None)
        mocker.patch.object(
            application,
            "futures_quote",
            return_value=선물시세(개인=0, 외인=0, 기관=0, 기타법인=0),
        )

        결과 = application.today_nets()

        assert [(n.market, n.futures) for n in 결과] == [
            (Market.KOSPI, True),
            (Market.KOSDAQ, True),
        ]

    def test_선물_투자자만_안_오면_칸은_남고_수급만_빈다(self, mocker):
        """KIS가 코스닥150 선물 투자자 조회에 간헐적으로 500을 준다.

        그때마다 칸을 빼면 카드가 넷이었다 셋이었다 한다. 시세는 왔으니 칸은 두고
        수급만 비운다 — 0으로 채우면 "아무도 안 샀다"는 거짓말이 된다.
        """
        mocker.patch.object(
            application.kiwoom_sector,
            "fetch_sector_net_buy",
            return_value=현물수급(개인=-1, 외인=1, 기관=0, 기타법인=0),
        )
        빈_투자자 = 선물시세(개인=0, 외인=0, 기관=0, 기타법인=0)
        mocker.patch.object(
            application,
            "futures_quote",
            return_value=type(빈_투자자)(**{**vars(빈_투자자), "investors": None}),
        )

        결과 = application.today_nets()

        선물들 = [n for n in 결과 if n.futures]
        assert len(선물들) == 2
        assert all(n.nets is None for n in 선물들)
        assert all(n.index_value == 943.20 for n in 선물들)

    def test_아무_값도_없으면_빈_목록이다(self, mocker):
        mocker.patch.object(application.kiwoom_sector, "fetch_sector_net_buy", return_value=None)
        mocker.patch.object(application, "futures_quote", return_value=None)

        assert application.today_nets() == []
