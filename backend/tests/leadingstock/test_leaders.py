"""첫 화면 주도주 선정 — 거래대금·등락률이 함께 높은 것만."""

from backend.leadingstock.domain import LeadingStocks, LeadingStockSnapshot


def 종목(이름: str, 거래대금: int, 등락률: float, 상한가: bool = False) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=이름, stock_name=이름, current_price=1000,
        price_change_rate=등락률, trading_value_rank=1,
        accumulated_trading_value=거래대금, limit_up=상한가,
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


class Test상한가_추리기:
    def test_후보_풀_전체에서_고른다(self):
        """상한가는 잠기면서 거래가 말라 점수가 낮다 — 탑5 안에서만 찾으면 대개 안 나온다."""
        pool = [
            종목("대장주", 9_000_000_000_000, 3.0),
            종목("잠긴잡주", 20_000_000_000, 29.9, 상한가=True),
        ]

        assert 이름들(LeadingStocks(pool).limit_ups()) == ["잠긴잡주"]
        assert "잠긴잡주" not in 이름들(LeadingStocks(pool).leaders(1))

    def test_많이_올랐어도_상한가가_아니면_빠진다(self):
        """신규상장 종목은 제한폭이 없어 +150%로도 상한가가 아니다 — 등락률로 가리지 않는다."""
        pool = [
            종목("신규상장주", 300_000_000_000, 153.0),
            종목("진짜상한가", 20_000_000_000, 29.94, 상한가=True),
        ]

        assert 이름들(LeadingStocks(pool).limit_ups()) == ["진짜상한가"]

    def test_받은_순서를_그대로_지킨다(self):
        """등수를 다투는 값이 아니라 "지금 잠겼다"는 상태라 다시 세우지 않는다."""
        pool = [
            종목("먼저", 90_000_000_000, 29.9, 상한가=True),
            종목("중간", 50_000_000_000, 3.0),
            종목("나중", 80_000_000_000, 30.0, 상한가=True),
        ]

        assert 이름들(LeadingStocks(pool).limit_ups()) == ["먼저", "나중"]

    def test_한_종목도_없으면_빈_목록이다(self):
        pool = [종목("평범주", 9_000_000_000_000, 3.0)]

        assert LeadingStocks(pool).limit_ups() == []


class Test제한폭이_넓은_날의_등락률:
    """상장 첫날처럼 등락률이 +30%를 넘으면 제한폭(+300%) 대비로 환산해 견준다."""

    def test_신규상장주가_공모가_대비_등락률로_1위를_차지하지_않는다(self):
        """+86.9%는 +300% 중 29% — 일반 종목 +8.7%와 같은 자리라, +12% 오른 종목보다 등락률 축에서 뒤진다."""
        pool = [
            종목("대장주", 9_000_000_000_000, 1.5),
            종목("신규상장주", 2_000_000_000_000, 86.9),   # 거래대금을 반으로 봐도 잘오른주보다 크게 — 등락률만 본다
            종목("잘오른주", 900_000_000_000, 12.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(3)) == ["대장주", "신규상장주", "잘오른주"]

    def test_제한폭_가까이_오른_신규상장주는_여전히_앞선다(self):
        """+250%는 제한폭의 83% — 일반 종목 +25%와 같은 자리라 등락률 축 1위다."""
        pool = [
            종목("대장주", 9_000_000_000_000, 1.5),
            종목("신규상장주", 2_000_000_000_000, 250.0),  # 거래대금을 반으로 봐도 잘오른주보다 크게 — 등락률만 본다
            종목("잘오른주", 900_000_000_000, 12.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(1)) == ["신규상장주"]

    def test_넘은_적이_있으면_30퍼센트_아래로_내려와도_계속_환산한다(self):
        """+100%에서 +26%로 밀려 내려온 신규상장주가 "+26% 급등주"로 튀어 오르지 않는다."""
        내려온 = LeadingStockSnapshot(
            stock_code="000001", stock_name="신규상장주", current_price=1, price_change_rate=26.0,
            trading_value_rank=3, accumulated_trading_value=900_000_000_000, wide_limit_day=True,
        )
        pool = [종목("대장주", 9_000_000_000_000, 1.5), 내려온, 종목("잘오른주", 1_000_000_000_000, 12.0)]

        assert 내려온.rate_for_ranking == 2.6
        assert 이름들(LeadingStocks(pool).leaders(3))[-1] == "신규상장주"   # +12% 오른 종목보다 등락률 축에서 뒤진다

    def test_상장_첫날은_거래대금을_반으로_견준다(self):
        """손바뀜으로 부풀려진 거래대금 — 등락률을 환산해도 거래대금 축만으로 1위를 지키지 못하게."""
        신규 = 종목("신규상장주", 2_546_0000_0000, 124.62)

        assert 신규.trading_value_for_ranking == 1_273_0000_0000
        assert 신규.accumulated_trading_value == 2_546_0000_0000   # 화면에 보이는 값은 그대로

    def test_넘은_적이_있으면_내려와도_거래대금을_반으로_견준다(self):
        내려온 = LeadingStockSnapshot(
            stock_code="000001", stock_name="신규상장주", current_price=1, price_change_rate=26.0,
            trading_value_rank=1, accumulated_trading_value=1_000, wide_limit_day=True,
        )

        assert 내려온.trading_value_for_ranking == 500

    def test_일반_종목의_거래대금은_그대로_견준다(self):
        assert 종목("대장주", 9_000, 1.5).trading_value_for_ranking == 9_000

    def test_신규상장주가_거래대금만으로_1위를_차지하지_않는다(self):
        """2026-10-01 10:08 실제 후보 일부 — 보정 없이는 브릴스가 1위였다."""
        pool = [
            종목("브릴스", 2_546_0000_0000, 124.62),
            종목("두산에너빌리티", 2_539_0000_0000, 1.23),
            종목("현대모비스", 2_032_0000_0000, 4.90),
            종목("덕산넵코어스", 1_799_0000_0000, 2.95),
            종목("한미반도체", 1_606_0000_0000, 1.56),
            종목("세미파이브", 1_177_0000_0000, 16.86),
        ]

        assert 이름들(LeadingStocks(pool).leaders(1)) != ["브릴스"]

    def test_넘은_적이_없는_26퍼센트는_그대로_견준다(self):
        assert 종목("급등주", 1_000_000_000_000, 26.0).rate_for_ranking == 26.0

    def test_보이는_등락률은_그대로다(self):
        """환산은 순위를 매길 때만 쓴다 — 화면에 +8.69%로 보이면 틀린 값이다."""
        pool = [종목("신규상장주", 1_000_000_000_000, 86.9)]

        assert LeadingStocks(pool).leaders(1)[0].price_change_rate == 86.9

    def test_상한가_30퍼센트는_환산하지_않는다(self):
        """제한폭 안의 끝값이다 — +30%를 +3%로 깎으면 상한가 종목이 이유 없이 밀린다."""
        pool = [
            종목("대장주", 3_000_000_000_000, 1.0),
            종목("상한가", 2_000_000_000_000, 30.0, 상한가=True),
            종목("조금오른주", 1_000_000_000_000, 5.0),
        ]

        assert 이름들(LeadingStocks(pool).leaders(1)) == ["상한가"]
