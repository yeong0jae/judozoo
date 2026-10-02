from datetime import datetime

from backend.overseasleadingstock.domain import OverseasStockRank, regular_session_open


def 종목(price=100.0, diff=5.0) -> OverseasStockRank:
    return OverseasStockRank(
        rank=1, exchange="NAS", symbol="AAA", name="AAA", ename="AAA CORP",
        price=price, diff=diff, rate=5.26, trading_value=1e9,
    )


class Test정규장_시가:
    def test_장전_거래는_건너뛰고_09시30분_첫_봉의_시가를_쓴다(self):
        봉 = [
            (datetime(2026, 10, 1, 9, 29), 99.0),
            (datetime(2026, 10, 1, 9, 31), 102.0),
            (datetime(2026, 10, 1, 9, 30), 101.0),
        ]

        assert regular_session_open(봉) == 101.0

    def test_아직_장전이면_시가가_없다(self):
        assert regular_session_open([(datetime(2026, 10, 1, 8, 0), 99.0)]) is None

    def test_분봉이_없으면_시가가_없다(self):
        assert regular_session_open([]) is None


class Test전일_종가:
    def test_현재가에서_대비를_빼면_전일_종가다(self):
        assert 종목(price=100.0, diff=5.0).previous_close == 95.0

    def test_내린_날은_대비가_음수라_전일_종가가_더_높다(self):
        assert 종목(price=100.0, diff=-4.0).previous_close == 104.0
