"""종목 카탈로그 검색 — Kotlin `StocksTest` 이관."""

from datetime import datetime

from backend.stock.domain import Market, OverseasStock, Stock, Stocks

AT = datetime(2026, 9, 12, 8, 30)


def 종목(code: str, name: str, market: Market = Market.KOSPI) -> Stock:
    return Stock(
        short_code=code,
        standard_code=f"KR7{code}",
        name=name,
        market=market,
        created_at=AT,
        updated_at=AT,
    )


삼성전자 = 종목("005930", "삼성전자")
삼성바이오 = 종목("207940", "삼성바이오로직스")
하이닉스 = 종목("000660", "SK하이닉스")
코덱스 = 종목("069500", "KODEX 200")
카탈로그 = Stocks([삼성전자, 삼성바이오, 하이닉스, 코덱스])


class Test종목_검색:
    def test_종목명_일부로_여러_종목을_찾는다(self):
        찾은것 = 카탈로그.search("삼성", limit=10)

        assert [s.name for s in 찾은것] == ["삼성바이오로직스", "삼성전자"]

    def test_종목코드_접두사로_찾는다(self):
        assert 카탈로그.search("0059", limit=10) == [삼성전자]

    def test_대소문자를_가리지_않는다(self):
        assert 카탈로그.search("kodex", limit=10) == [코덱스]

    def test_종목명_시작_일치를_코드_시작_일치보다_앞에_둔다(self):
        이름이_먼저 = 종목("999999", "ABC전자")      # 종목명이 "abc"로 시작 → 관련도 0
        코드가_먼저 = 종목("ABC123", "주식회사가나")  # 종목코드가 "abc"로 시작 → 관련도 1

        순위 = Stocks([코드가_먼저, 이름이_먼저]).search("abc", limit=10)

        assert 순위 == [이름이_먼저, 코드가_먼저]

    def test_최대_건수를_초과하지_않는다(self):
        assert len(카탈로그.search("삼성", limit=1)) == 1

    def test_공백_질의는_빈_결과(self):
        assert 카탈로그.search("   ", limit=10) == []

    def test_일치하는_종목이_없으면_빈_결과(self):
        assert 카탈로그.search("없는종목", limit=10) == []


def 해외종목(symbol: str, name: str, english: str, exchange: str = "NAS") -> OverseasStock:
    return OverseasStock(
        exchange=exchange, symbol=symbol, name=name, english_name=english,
        created_at=AT, updated_at=AT,
    )


class Test해외_종목_검색:
    애플 = 해외종목("AAPL", "애플", "APPLE INC")

    def test_심볼로_찾는다(self):
        assert self.애플.matches("aapl")

    def test_한글명으로_찾는다(self):
        assert self.애플.matches("애플")

    def test_영문명으로_찾는다(self):
        assert self.애플.matches("apple")

    def test_무관한_질의는_매칭되지_않는다(self):
        assert not self.애플.matches("삼성")
