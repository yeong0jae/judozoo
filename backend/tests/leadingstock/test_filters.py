"""주도주 후보 필터 — Kotlin Kotest 필터 테스트 이관.

Kotest는 `LeadingStockCriteriaProperties()`의 **코드 기본값**(30위·5%)으로 돌았는데,
운영은 `application.yaml`이 덮어쓴 값(35위·7%)을 쓴다. 즉 기존 테스트는 운영에서 쓰이지 않는
숫자를 검증하고 있었다. 여기서는 기준값을 **테스트마다 명시**해 그 괴리를 없앤다.
"""

from datetime import date, datetime

import pytest

from backend.leadingstock import filters as f
from backend.leadingstock.domain import DailyCandle, LeadingStockSnapshot, MinuteCandle
from backend.settings import LeadingStockCriteria


def 기준(**덮어쓰기) -> LeadingStockCriteria:
    return LeadingStockCriteria(**덮어쓰기)


def 종목(
    stock_code="005930", stock_name="삼성전자", current_price=70_000,
    price_change_rate=5.0, trading_value_rank=1, accumulated_trading_value=1_000_000_000_000,
    market_cap=5000, opening_price=70_000, previous_close=67_000,
    high_price=71_000, low_price=69_000, program_net_buy=0,
) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=stock_code, stock_name=stock_name, current_price=current_price,
        price_change_rate=price_change_rate, trading_value_rank=trading_value_rank,
        accumulated_trading_value=accumulated_trading_value, market_cap=market_cap,
        opening_price=opening_price, previous_close=previous_close,
        high_price=high_price, low_price=low_price, program_net_buy=program_net_buy,
    )


def 일봉(open_price=70_000, high_price=71_000, low_price=69_000, close_price=70_500,
        volume=1_000_000, change_rate=1.0, on=date(2026, 5, 23)) -> DailyCandle:
    return DailyCandle(on, open_price, high_price, low_price, close_price, volume, change_rate)


def 분봉(open_price=70_000, high_price=70_100, low_price=69_900, close_price=70_050,
        volume=10_000, trading_value=700_000_000, at=datetime(2026, 5, 23, 10, 0)) -> MinuteCandle:
    return MinuteCandle(at, open_price, high_price, low_price, close_price, volume, trading_value)


class Test거래대금순위:
    필터 = f.TradingValueRankFilter(기준(max_trading_value_rank=35))

    @pytest.mark.parametrize("순위,통과", [(1, True), (35, True), (36, False)])
    def test_상위_N위_이내만_통과한다(self, 순위, 통과):
        assert self.필터.filter(종목(trading_value_rank=순위)) is 통과

    def test_평가_문구가_화면에_나가는_모양_그대로다(self):
        결과 = self.필터.evaluate(종목(trading_value_rank=3))

        assert 결과.criteria_description == "상위 35위 이내"
        assert 결과.actual_value == "3위"
        assert 결과.passed is True


class Test당일등락률:
    필터 = f.DailyPriceChangeFilter(기준(min_daily_price_change_rate=7.0))

    @pytest.mark.parametrize("등락률,통과", [(7.0, True), (7.1, True), (6.9, False), (-3.0, False)])
    def test_기준_이상만_통과한다(self, 등락률, 통과):
        assert self.필터.filter(종목(price_change_rate=등락률)) is 통과

    def test_부호를_붙여_표시한다(self):
        assert self.필터.evaluate(종목(price_change_rate=7.25)).actual_value == "+7.25%"


class Test시가총액:
    필터 = f.MarketCapFilter(기준(min_market_cap=3000))

    @pytest.mark.parametrize("시총,통과", [(3000, True), (5000, True), (2999, False), (0, False)])
    def test_기준선_이상만_통과한다(self, 시총, 통과):
        assert self.필터.filter(종목(market_cap=시총)) is 통과

    def test_억원을_원으로_환산해_표시한다(self):
        결과 = self.필터.evaluate(종목(market_cap=5000))

        assert 결과.criteria_description == "3,000억원 이상"
        assert 결과.actual_value == "5,000억원"


class Test시가_대비_현재가:
    필터 = f.PriceAboveOpenFilter()

    def test_현재가가_시가_이상이면_통과(self):
        assert self.필터.filter(종목(current_price=71_000, opening_price=70_000)) is True

    def test_시가와_같아도_통과(self):
        assert self.필터.filter(종목(current_price=70_000, opening_price=70_000)) is True

    def test_시가보다_낮으면_차단(self):
        assert self.필터.filter(종목(current_price=69_000, opening_price=70_000)) is False

    def test_시가가_없으면_차단하고_문구로_알린다(self):
        결과 = self.필터.evaluate(종목(opening_price=0))

        assert 결과.passed is False
        assert 결과.actual_value == "시가 없음"


class Test전일_등락률:
    def 필터(self, 일봉들):
        return f.PrevDayCloseFilter(기준(max_prev_close_change_rate=25.0), lambda _c: 일봉들)

    def test_어제_등락률이_기준_이하면_통과(self):
        """일봉[0]=오늘, [1]=어제."""
        assert self.필터([일봉(change_rate=1.0), 일봉(change_rate=20.0)]).filter(종목()) is True

    def test_경계값도_통과(self):
        assert self.필터([일봉(), 일봉(change_rate=25.0)]).filter(종목()) is True

    def test_어제_급등이면_차단(self):
        """전날 이미 크게 오른 종목은 따라붙기 위험하다."""
        assert self.필터([일봉(), 일봉(change_rate=29.9)]).filter(종목()) is False

    def test_일봉이_부족하면_차단하고_문구로_알린다(self):
        결과 = self.필터([일봉()]).evaluate(종목())

        assert 결과.passed is False
        assert 결과.actual_value == "데이터 부족"


class Test시초가:
    def 필터(self, 일봉들):
        return f.OpeningPriceFilter(기준(max_opening_price_change_rate=7.0), lambda _c: 일봉들)

    def test_시초가_갭이_기준_이하면_통과(self):
        # 어제 종가 70,000 → 오늘 시가 73,000 = +4.29%
        assert self.필터([일봉(open_price=73_000), 일봉(close_price=70_000)]).filter(종목()) is True

    def test_시초가_갭이_크면_차단(self):
        # 70,000 → 77,000 = +10%
        assert self.필터([일봉(open_price=77_000), 일봉(close_price=70_000)]).filter(종목()) is False

    def test_가격과_갭을_함께_표시한다(self):
        결과 = self.필터([일봉(open_price=73_000), 일봉(close_price=70_000)]).evaluate(종목())

        assert 결과.actual_value == "73,000원 (+4.29%)"

    def test_일봉이_부족하면_차단(self):
        assert self.필터([일봉()]).filter(종목()) is False

    def test_어제_종가가_0이면_차단(self):
        assert self.필터([일봉(open_price=73_000), 일봉(close_price=0)]).filter(종목()) is False


class TestETF_제외:
    필터 = f.EtfExclusionFilter()

    def test_일반_종목명은_통과(self):
        assert self.필터.filter(종목(stock_name="삼성전자")) is True

    @pytest.mark.parametrize(
        "이름", ["KODEX 200", "TIGER 반도체", "KOSEF 코스피", "ACE 미국S&P500",
                "SOL AI반도체TOP2플러스", "SOL 미국배당다우존스"],
    )
    def test_운용사_브랜드로_시작하면_차단(self, 이름):
        assert self.필터.filter(종목(stock_name=이름)) is False

    def test_이름_중간의_ETN도_차단(self):
        assert self.필터.filter(종목(stock_name="한투 ETN 코스피200 H")) is False

    def test_브랜드와_비슷하지만_다르면_통과한다(self):
        """접두사는 공백까지 정확히 맞아야 한다 — "ACEM"은 "ACE "가 아니다."""
        assert self.필터.filter(종목(stock_name="ACEM헬스케어")) is True


class Test스팩_제외:
    필터 = f.SpacExclusionFilter()

    @pytest.mark.parametrize("이름", ["메리츠제2호스팩", "교보15호스팩"])
    def test_이름에_스팩이_들어가면_차단(self, 이름):
        assert self.필터.filter(종목(stock_name=이름)) is False

    def test_일반_종목은_통과(self):
        assert self.필터.filter(종목(stock_name="삼성전자")) is True


class Test최근_고가_대비:
    def 필터(self, 일봉들):
        return f.DailyHighPositionFilter(기준(max_high_position_drop_rate=-5.0), lambda _c: 일봉들)

    def test_고가에서_많이_안_빠졌으면_통과(self):
        # 고가 71,000 대비 현재가 70,000 = -1.41%
        assert self.필터([일봉(high_price=71_000)]).filter(종목(current_price=70_000)) is True

    def test_고가에서_크게_빠졌으면_차단(self):
        # 고가 100,000 대비 70,000 = -30%
        assert self.필터([일봉(high_price=100_000)]).filter(종목(current_price=70_000)) is False

    def test_일봉이_없으면_차단(self):
        assert self.필터([]).filter(종목()) is False

    def test_여러_봉_중_최고가를_기준으로_삼는다(self):
        일봉들 = [일봉(high_price=71_000), 일봉(high_price=100_000), 일봉(high_price=72_000)]

        assert self.필터(일봉들).filter(종목(current_price=70_000)) is False


class Test1분봉_거래대금:
    def 필터(self, 분봉들):
        return f.MinuteCandleVolumeFilter(
            기준(min_minute_trading_value=5_000_000_000, min_minute_volume_increase_rate=500.0),
            lambda _c: 분봉들,
        )

    def test_최신봉이_기준액_이상이고_평균_대비_급증하면_통과(self):
        분봉들 = [분봉(trading_value=10_000_000_000)] + [분봉(trading_value=1_000_000_000)] * 9
        # 평균 1.9e9, 최신 1e10 → 약 526%
        assert self.필터(분봉들).filter(종목()) is True

    def test_최신봉이_기준액에_못_미치면_차단(self):
        분봉들 = [분봉(trading_value=1_000_000_000)] * 10

        assert self.필터(분봉들).filter(종목()) is False

    def test_기준액을_넘어도_평균_대비_증가율이_낮으면_차단(self):
        분봉들 = [분봉(trading_value=6_000_000_000)] * 10  # 전부 같으면 100%

        assert self.필터(분봉들).filter(종목()) is False

    def test_분봉이_없으면_차단(self):
        assert self.필터([]).filter(종목()) is False


class Test1분봉_등락률:
    def 필터(self, 분봉들):
        return f.MinuteCandleFluctuationFilter(
            기준(max_minute_fluctuation_rate=4.0), lambda _c: 분봉들
        )

    def test_변동이_작으면_통과(self):
        assert self.필터([분봉(open_price=70_000, close_price=70_700)]).filter(종목()) is True  # +1%

    def test_급등이면_차단(self):
        assert self.필터([분봉(open_price=70_000, close_price=74_000)]).filter(종목()) is False  # +5.7%

    def test_급락도_차단한다(self):
        """절대값으로 본다 — 방향과 무관하게 변동이 크면 들어가지 않는다."""
        assert self.필터([분봉(open_price=70_000, close_price=66_000)]).filter(종목()) is False

    def test_시가가_0이면_차단(self):
        assert self.필터([분봉(open_price=0)]).filter(종목()) is False


class Test프로그램_순매수:
    def 필터(self, 순매수):
        return f.ProgramNetBuyFilter(기준(min_program_net_buy=-10_000), lambda _c: 순매수)

    @pytest.mark.parametrize("순매수,통과", [(5_000, True), (-10_000, True), (-10_001, False)])
    def test_기준_이상만_통과한다(self, 순매수, 통과):
        assert self.필터(순매수).filter(종목()) is 통과

    def test_백만원을_원으로_환산해_만원까지_표시한다(self):
        결과 = self.필터(1_234).evaluate(종목())

        assert 결과.actual_value == "12억 3,400만원"


class Test필터_체인:
    class 전부통과(f.StockFilter):
        name = "all-pass"
        def filter(self, stock): return True
        def evaluate(self, stock): return f.FilterEvaluationResult(self.name, "전부 통과", "ok", True)

    class 전부차단(f.StockFilter):
        name = "all-reject"
        def filter(self, stock): return False
        def evaluate(self, stock): return f.FilterEvaluationResult(self.name, "전부 차단", "no", False)

    def 코드허용(self, *codes):
        class 허용(f.StockFilter):
            name = "code-allowlist"
            def filter(self, stock): return stock.stock_code in codes
            def evaluate(self, stock):
                return f.FilterEvaluationResult(self.name, ",".join(codes), stock.stock_code, self.filter(stock))
        return 허용()

    A, B, C = 종목(stock_code="A"), 종목(stock_code="B"), 종목(stock_code="C")

    def test_입력이_비어있으면_빈_결과(self):
        assert f.FilterChain([self.전부통과()]).apply([]) == []

    def test_필터가_전부_통과면_입력_그대로(self):
        체인 = f.FilterChain([self.전부통과(), self.전부통과()])

        assert 체인.apply([self.A, self.B, self.C]) == [self.A, self.B, self.C]

    def test_필터가_없으면_입력_그대로(self):
        assert f.FilterChain([]).apply([self.A, self.B]) == [self.A, self.B]

    def test_앞_필터가_떨군_종목은_뒤로_넘어가지_않는다(self):
        체인 = f.FilterChain([self.코드허용("A", "B"), self.코드허용("B", "C")])

        assert 체인.apply([self.A, self.B, self.C]) == [self.B]

    def test_중간에_비면_뒤_필터는_호출되지_않는다(self):
        호출됨 = []

        class 뒤필터(f.StockFilter):
            name = "after-reject"
            def filter(self, stock):
                호출됨.append(stock)
                return True
            def evaluate(self, stock):
                return f.FilterEvaluationResult(self.name, "noop", "noop", True)

        체인 = f.FilterChain([self.전부통과(), self.전부차단(), 뒤필터()])

        assert 체인.apply([self.A, self.B, self.C]) == []
        assert 호출됨 == []
