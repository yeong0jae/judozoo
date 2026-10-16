package judozoo.leadingstock.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

/** 주도주 후보 필터 — 평가 문구는 화면에 그대로 나간다. 기준값은 운영 기본값이다. */
class StockFilterTest {
    private val criteria = LeadingStockCriteria()

    private fun stock(
        name: String = "삼성전자",
        currentPrice: Long = 70_000,
        rate: Double = 5.0,
        rank: Int = 1,
        marketCap: Long = 5000,
        openingPrice: Long = 70_000,
    ) = LeadingStockSnapshot(
        stockCode = "005930", stockName = name, currentPrice = currentPrice, priceChangeRate = rate, tradingValueRank = rank,
        accumulatedTradingValue = 1_000_000_000_000, marketCap = marketCap, openingPrice = openingPrice,
    )

    private fun daily(open: Long = 70_000, high: Long = 71_000, close: Long = 70_500, changeRate: Double = 1.0, on: LocalDate = LocalDate.of(2026, 5, 23)) =
        DailyCandle(on, open, high, 69_000, close, 1_000_000, changeRate)

    @Nested
    inner class 거래대금순위 {
        private val filter = StockFilter.TradingValueRank(criteria)

        @ParameterizedTest
        @CsvSource("1,true", "35,true", "36,false")
        fun `상위 35위 이내만 통과한다`(rank: Int, passed: Boolean) {
            filter.passes(stock(rank = rank)) shouldBe passed
        }

        @Test
        fun `평가 문구가 화면에 나가는 모양 그대로다`() {
            filter.evaluate(stock(rank = 3)) shouldBe FilterResult("거래대금순위", "상위 35위 이내", "3위", true, 3.0, 35.0)
        }
    }

    @Nested
    inner class 당일등락률 {
        private val filter = StockFilter.DailyPriceChange(criteria)

        @ParameterizedTest
        @CsvSource("7.0,true", "7.1,true", "6.9,false", "-3.0,false")
        fun `기준 이상만 통과한다`(rate: Double, passed: Boolean) {
            filter.passes(stock(rate = rate)) shouldBe passed
        }

        @Test
        fun `부호를 붙여 표시하고 눈금 막대용 숫자도 준다`() {
            filter.evaluate(stock(rate = 7.25)) shouldBe FilterResult("당일등락률", "7.0% 이상", "+7.25%", true, 7.25, 7.0)
        }

        /** Python은 이진값 그대로 짝수 쪽으로 반올림한다 — 1.005는 실제로 1.00499…라 내려간다. */
        @Test
        fun `소수 둘째 자리 반올림이 Python과 같다`() {
            filter.evaluate(stock(rate = 1.005)).actualValue shouldBe "+1.00%"
            filter.evaluate(stock(rate = 0.125)).actualValue shouldBe "+0.12%"
        }
    }

    @Nested
    inner class 시가총액 {
        private val filter = StockFilter.MarketCap(criteria)

        @ParameterizedTest
        @CsvSource("3000,true", "5000,true", "2999,false", "0,false")
        fun `기준선 이상만 통과한다`(marketCap: Long, passed: Boolean) {
            filter.passes(stock(marketCap = marketCap)) shouldBe passed
        }

        @Test
        fun `억원을 원으로 환산해 표시한다`() {
            val result = filter.evaluate(stock(marketCap = 5000))

            result.criteriaDescription shouldBe "3,000억원 이상"
            result.actualValue shouldBe "5,000억원"
        }
    }

    @Nested
    inner class 시가_대비_현재가 {
        private val filter = StockFilter.PriceAboveOpen

        @Test
        fun `현재가가 시가 이상이면 통과하고 같아도 통과한다`() {
            filter.passes(stock(currentPrice = 71_000, openingPrice = 70_000)) shouldBe true
            filter.passes(stock(currentPrice = 70_000, openingPrice = 70_000)) shouldBe true
        }

        @Test
        fun `시가보다 낮으면 차단`() {
            filter.passes(stock(currentPrice = 69_000, openingPrice = 70_000)) shouldBe false
        }

        @Test
        fun `시가가 없으면 차단하고 문구로 알린다`() {
            filter.evaluate(stock(openingPrice = 0)) shouldBe FilterResult("시가 대비 현재가", "현재가 ≥ 시가", "시가 없음", false, null, 0.0)
        }

        @Test
        fun `눈금 막대용으로 시가 대비 등락률을 숫자로도 준다`() {
            val result = filter.evaluate(stock(currentPrice = 77_000, openingPrice = 70_000))

            result.value!! shouldBe (10.0 plusOrMinus 1e-9)
            result.threshold shouldBe 0.0
        }
    }

    /** 일봉[0]은 오늘, [1]은 어제다. 전날 이미 크게 오른 종목은 따라붙기 위험하다. */
    @Nested
    inner class 전일_등락률 {
        private fun filter(vararg candles: DailyCandle) = StockFilter.PrevDayClose(criteria, candles.toList())

        @Test
        fun `어제 등락률이 기준 이하면 통과하고 경계값도 통과한다`() {
            filter(daily(), daily(changeRate = 20.0)).passes(stock()) shouldBe true
            filter(daily(), daily(changeRate = 25.0)).passes(stock()) shouldBe true
        }

        @Test
        fun `어제 급등이면 차단`() {
            filter(daily(), daily(changeRate = 29.9)).passes(stock()) shouldBe false
        }

        @Test
        fun `일봉이 부족하면 차단하고 문구로 알린다`() {
            filter(daily()).evaluate(stock()) shouldBe FilterResult("전일 등락률", "25.0% 이하", "데이터 부족", false, null, 25.0)
        }

        @Test
        fun `눈금 막대용으로 어제 등락률과 기준을 숫자로도 준다`() {
            filter(daily(), daily(changeRate = 12.5)).evaluate(stock()) shouldBe FilterResult("전일 등락률", "25.0% 이하", "+12.50%", true, 12.5, 25.0)
        }
    }

    @Nested
    inner class 시초가 {
        private fun filter(vararg candles: DailyCandle) = StockFilter.OpeningPrice(criteria, candles.toList())

        /** 어제 종가 70,000 → 오늘 시가 73,000 = +4.29% */
        @Test
        fun `시초가 갭이 기준 이하면 통과하고 가격과 갭을 함께 표시한다`() {
            val result = filter(daily(open = 73_000), daily(close = 70_000)).evaluate(stock())

            result.passed shouldBe true
            result.criteriaDescription shouldBe "시초가 7.0% 이하"
            result.actualValue shouldBe "73,000원 (+4.29%)"
            result.value!! shouldBe (4.2857 plusOrMinus 1e-3)
        }

        @Test
        fun `시초가 갭이 크면 차단`() {
            filter(daily(open = 77_000), daily(close = 70_000)).passes(stock()) shouldBe false
        }

        @Test
        fun `일봉이 부족하면 차단`() {
            filter(daily()).evaluate(stock()).run { passed shouldBe false; actualValue shouldBe "데이터 부족" }
        }

        @Test
        fun `어제 종가가 0이면 차단`() {
            filter(daily(open = 73_000), daily(close = 0)).evaluate(stock()).run { passed shouldBe false; actualValue shouldBe "시초가 없음" }
        }
    }

    @Nested
    inner class ETF_제외 {
        @Test
        fun `일반 종목명은 통과`() {
            StockFilter.EtfExclusion.passes(stock(name = "삼성전자")) shouldBe true
        }

        @ParameterizedTest
        @ValueSource(strings = ["KODEX 200", "TIGER 반도체", "KOSEF 코스피", "ACE 미국S&P500", "SOL AI반도체TOP2플러스", "SOL 미국배당다우존스"])
        fun `운용사 브랜드로 시작하면 차단`(name: String) {
            StockFilter.EtfExclusion.passes(stock(name = name)) shouldBe false
        }

        @Test
        fun `이름 중간의 ETN도 차단`() {
            StockFilter.EtfExclusion.passes(stock(name = "한투 ETN 코스피200 H")) shouldBe false
        }

        /** 접두사는 공백까지 정확히 맞아야 한다 — "ACEM"은 "ACE "가 아니다. */
        @Test
        fun `브랜드와 비슷하지만 다르면 통과한다`() {
            StockFilter.EtfExclusion.passes(stock(name = "ACEM헬스케어")) shouldBe true
        }
    }

    @Nested
    inner class 스팩_제외 {
        @ParameterizedTest
        @ValueSource(strings = ["메리츠제2호스팩", "교보15호스팩"])
        fun `이름에 스팩이 들어가면 차단`(name: String) {
            StockFilter.SpacExclusion.passes(stock(name = name)) shouldBe false
        }

        @Test
        fun `일반 종목은 통과`() {
            StockFilter.SpacExclusion.passes(stock(name = "삼성전자")) shouldBe true
        }
    }

    @Nested
    inner class 최근_고가_대비 {
        private val asOf = LocalDate.of(2026, 5, 24)

        private fun filter(vararg candles: DailyCandle) = StockFilter.DailyHighPosition(criteria, candles.toList(), asOf)

        /** 고가 71,000 대비 현재가 70,000 = -1.41% */
        @Test
        fun `고가에서 많이 안 빠졌으면 통과`() {
            filter(daily(high = 71_000)).passes(stock(currentPrice = 70_000)) shouldBe true
        }

        @Test
        fun `고가에서 크게 빠졌으면 차단`() {
            filter(daily(high = 100_000)).passes(stock(currentPrice = 70_000)) shouldBe false
        }

        @Test
        fun `일봉이 없으면 차단`() {
            filter().evaluate(stock()).run { passed shouldBe false; actualValue shouldBe "데이터 없음"; value.shouldBeNull() }
        }

        @Test
        fun `여러 봉 중 최고가를 기준으로 삼는다`() {
            filter(daily(high = 71_000), daily(high = 100_000), daily(high = 72_000)).passes(stock(currentPrice = 70_000)) shouldBe false
        }

        @Test
        fun `오늘 찍은 고가는 제외한다`() {
            val result = filter(daily(high = 100_000, on = asOf), daily(high = 71_000)).evaluate(stock(currentPrice = 70_000))

            result.passed shouldBe true
            result.criteriaDescription shouldBe "전일까지 최근 60거래일 고가 대비 현재가 -5.0% 이상"
            result.actualValue shouldBe "-1.41%"
        }

        @Test
        fun `전일까지의 가장 최근 60봉만 쓴다`() {
            val candles = (0L until 60).map { daily(high = 71_000, on = LocalDate.of(2026, 5, 23).minusDays(it)) } +
                daily(high = 100_000, on = LocalDate.of(2026, 3, 24))

            StockFilter.DailyHighPosition(criteria, candles, asOf).passes(stock(currentPrice = 70_000)) shouldBe true
        }
    }

    @Nested
    inner class 프로그램_양매수 {
        @ParameterizedTest
        @CsvSource("5000,true", "0,true", "-1,false")
        fun `순매수인 종목만 통과한다`(netBuy: Long, passed: Boolean) {
            StockFilter.ProgramNetBuy(criteria, netBuy).passes(stock()) shouldBe passed
        }

        @Test
        fun `백만원을 원으로 환산해 만원까지 표시한다`() {
            val result = StockFilter.ProgramNetBuy(criteria, 1_234).evaluate(stock())

            result.criteriaDescription shouldBe "0원 이상"
            result.actualValue shouldBe "12억 3,400만원"
        }
    }
}
