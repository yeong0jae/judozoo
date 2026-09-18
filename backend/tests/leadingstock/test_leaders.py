"""첫 화면 주도주 선정 — 거래대금·등락률이 함께 높은 것만."""

from backend.leadingstock.domain import LeadingStocks, LeadingStockSnapshot


def 종목(이름: str, 거래대금: int, 등락률: float) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=이름, stock_name=이름, current_price=1000,
        price_change_rate=등락률, trading_value_rank=1,
        accumulated_trading_value=거래대금,
    )


def 이름들(stocks):
    return [s.stock_name for s in stocks]


class Test주도주_선정:
    def test_상한가_잡주가_두_축_모두_상위인_종목을_이기지_못한다(self):
        """거래대금 꼴찌인 상한가 한 종목이 첫 화면 맨 위를 차지하면 안 된다."""
        pool = [
            종목("대장주", 7_000_000_000_000, 4.0),
            종목("둘다상위", 900_000_000_000, 15.0),
            종목("상한가잡주", 100_000_000_000, 30.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(3))[-1] == "상한가잡주"

    def test_거래대금이_크면_덜_올라도_앞선다(self):
        """두 축의 등수가 맞바뀌면 거래대금 쪽이 이긴다 — 무게가 그쪽으로 기울어 있다."""
        pool = [
            종목("대장주", 9_000_000_000_000, 1.0),
            종목("잘오른중형주", 1_000_000_000_000, 9.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(2)) == ["대장주", "잘오른중형주"]

    def test_등락률이_바닥이면_거래대금_1위도_밀린다(self):
        """기하평균이라 한 축이 바닥이면 함께 깎인다 — 거래대금만으로 세우는 것과 다르다."""
        pool = [종목(f"중위권{i}", (10 - i) * 100_000_000_000, 2.0 + i) for i in range(1, 9)]
        pool += [
            종목("거래대금1위", 9_000_000_000_000, 0.1),
            종목("둘다상위", 1_000_000_000_000, 20.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(1)) == ["둘다상위"]

    def test_내린_종목은_거래대금이_아무리_커도_빠진다(self):
        pool = [
            종목("돈만몰린하락주", 9_000_000_000_000, -3.0),
            종목("보합", 8_000_000_000_000, 0.0),
            종목("오른종목", 100_000_000_000, 2.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(5)) == ["오른종목"]

    def test_오른_종목이_없는_날은_빈_목록이다(self):
        pool = [종목("하락주", 9_000_000_000_000, -3.0)]

        assert LeadingStocks(pool).leaders(5) == []
