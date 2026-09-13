"""종목 검색 API — Kotlin `StockControllerTest` 이관 + 응답 계약 확인."""

from datetime import datetime

import pytest

from backend.stock import application
from backend.stock.domain import Market, OverseasStock, Stock, Stocks

AT = datetime(2026, 9, 12, 8, 30)


def 종목(code: str, name: str) -> Stock:
    return Stock(
        short_code=code, standard_code=f"KR7{code}", name=name,
        market=Market.KOSPI, created_at=AT, updated_at=AT,
    )


@pytest.fixture
def 카탈로그_적재(monkeypatch):
    monkeypatch.setattr(
        application, "_domestic",
        Stocks([종목("005930", "삼성전자"), 종목("207940", "삼성바이오로직스")]),
    )
    monkeypatch.setattr(
        application, "_overseas",
        [OverseasStock(
            exchange="NAS", symbol="AAPL", name="애플", english_name="APPLE INC",
            created_at=AT, updated_at=AT,
        )],
    )


class Test종목_검색_API:
    def test_검색_결과_목록을_200으로_반환한다(self, 로그인_client, 카탈로그_적재):
        응답 = 로그인_client.get("/api/stocks/search", params={"q": "삼성"})

        assert 응답.status_code == 200
        본문 = 응답.json()
        assert 본문["code"] == "SUCCESS"
        assert len(본문["data"]) == 2
        # 관련도가 같으면 종목명 사전순 — 바 < 전
        assert [r["stockName"] for r in 본문["data"]] == ["삼성바이오로직스", "삼성전자"]
        assert 본문["data"][1]["stockCode"] == "005930"

    def test_결과가_없으면_빈_배열을_반환한다(self, 로그인_client, 카탈로그_적재):
        응답 = 로그인_client.get("/api/stocks/search", params={"q": "없는종목"})

        assert 응답.status_code == 200
        assert 응답.json()["data"] == []

    def test_국내_종목은_거래소가_비어있다(self, 로그인_client, 카탈로그_적재):
        결과 = 로그인_client.get("/api/stocks/search", params={"q": "삼성전자"}).json()["data"]

        assert 결과[0]["exchange"] is None

    def test_해외_종목은_거래소가_붙는다(self, 로그인_client, 카탈로그_적재):
        결과 = 로그인_client.get("/api/stocks/search", params={"q": "AAPL"}).json()["data"]

        assert (결과[0]["stockCode"], 결과[0]["exchange"]) == ("AAPL", "NAS")

    def test_국내와_해외를_함께_돌려준다(self, 로그인_client, 카탈로그_적재):
        결과 = 로그인_client.get("/api/stocks/search", params={"q": "애플"}).json()["data"]

        assert [r["stockName"] for r in 결과] == ["애플"]

    def test_질의가_없으면_400을_준다(self, 로그인_client, 카탈로그_적재):
        """Kotlin은 필수 @RequestParam 누락을 400으로 준다."""
        응답 = 로그인_client.get("/api/stocks/search")

        assert 응답.status_code == 400
        assert 응답.json()["code"] == "INVALID_PARAMETER"


class Test종목_투자자_수급_API:
    def test_백만원_단위로_그대로_돌려준다(self, 로그인_client, monkeypatch):
        """억으로 반올림하면 기관 세부 같은 작은 수급이 0으로 뭉개진다."""
        from backend.platform.kiwoom.investor import InvestorTrendDay

        monkeypatch.setattr(
            "backend.platform.kiwoom.investor.fetch_investor_trend",
            lambda _c: [
                InvestorTrendDay(
                    date="2026-09-11", individual_net=1200, foreign_net=-800,
                    institution_net=-400, other_corp_net=0,
                    financial_investment_net=100, insurance_net=-50, other_finance_net=0,
                    trust_net=200, private_equity_net=-20, pension_fund_net=150, bank_net=-10,
                )
            ],
        )

        데이터 = 로그인_client.get(
            "/api/stocks/005930/investor/daily", params={"count": 5}
        ).json()["data"]

        assert len(데이터) == 1
        assert 데이터[0]["date"] == "2026-09-11"
        assert 데이터[0]["individualMillion"] == 1200
        assert 데이터[0]["foreignMillion"] == -800
        assert 데이터[0]["breakdown"]["pensionFundMillion"] == 150

    def test_날짜_형식이_어긋난_행은_버린다(self, 로그인_client, monkeypatch):
        from backend.platform.kiwoom.investor import InvestorTrendDay

        monkeypatch.setattr(
            "backend.platform.kiwoom.investor.fetch_investor_trend",
            lambda _c: [
                InvestorTrendDay(date="", individual_net=0, foreign_net=0, institution_net=0),
            ],
        )

        assert 로그인_client.get("/api/stocks/005930/investor/daily").json()["data"] == []
