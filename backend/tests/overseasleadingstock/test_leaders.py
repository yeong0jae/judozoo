"""첫 화면 해외 주도주 선정 — 국내와 같은 규칙."""

from backend.overseasleadingstock.domain import OverseasStockRank, OverseasStockRanks


def 종목(심볼: str, 거래대금: float, 등락률: float) -> OverseasStockRank:
    return OverseasStockRank(
        rank=1, exchange="NAS", symbol=심볼, name=심볼, ename=심볼,
        price=100.0, diff=1.0, rate=등락률, trading_value=거래대금,
    )


def 심볼들(stocks):
    return [s.symbol for s in stocks]


class Test해외_주도주_선정:
    def test_두_축이_모두_높은_것이_앞선다(self):
        pool = [
            종목("BIG", 9_000_000_000, 2.0),
            종목("BOTH", 5_000_000_000, 9.0),
            종목("HOT", 100_000_000, 40.0),
        ]

        assert 심볼들(OverseasStockRanks(pool).leaders(1)) == ["BOTH"]

    def test_내린_종목은_빠진다(self):
        pool = [종목("DOWN", 9_000_000_000, -1.0), 종목("UP", 100_000_000, 1.0)]

        assert 심볼들(OverseasStockRanks(pool).leaders(5)) == ["UP"]
