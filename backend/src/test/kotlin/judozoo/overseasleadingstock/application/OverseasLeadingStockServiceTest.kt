package judozoo.overseasleadingstock.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.library.EntityNotFoundException
import judozoo.market.application.MarketCalendar
import judozoo.platform.kis.KisOverseasChart
import judozoo.platform.kis.KisOverseasChart.OverseasMinuteCandle
import judozoo.platform.kis.KisOverseasProduct
import judozoo.platform.kis.KisOverseasRanking
import judozoo.platform.kis.KisOverseasRanking.OverseasRankItem
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class OverseasLeadingStockServiceTest {
    private val ranking = mockk<KisOverseasRanking>()
    private val product = mockk<KisOverseasProduct>()
    private val minutes = mockk<OverseasMinutes>()

    private var usStatus = false to true
    private val calendar = mockk<MarketCalendar> {
        every { usMarketStatus() } answers { usStatus }
        every { untilUsSession() } returns Duration.ofHours(7)
    }
    private val service = OverseasLeadingStockService(
        ranking, product, mockk<KisOverseasChart>(), minutes, mockk<OverseasMinuteArchive>(), calendar,
        Clock.fixed(Instant.parse("2026-10-01T14:00:00Z"), ZoneOffset.UTC),
    )

    private fun row(symb: String, tamt: String, rate: String = "10.0", ename: String = "SOME CORP", sign: String = "2") =
        OverseasRankItem("1", "NAS", symb, symb, "100", sign, "5", rate, "1000", tamt, ename)

    private fun exchanges(nas: List<OverseasRankItem>, nys: List<OverseasRankItem> = emptyList(), ams: List<OverseasRankItem> = emptyList()) {
        every { ranking.fetchTradingValueRanking("NAS") } returns nas
        every { ranking.fetchTradingValueRanking("NYS") } returns nys
        every { ranking.fetchTradingValueRanking("AMS") } returns ams
    }

    @Nested
    inner class 랭킹 {
        @Test
        fun `세 거래소를 합쳐 거래대금 내림차순으로 순위를 매긴다`() {
            exchanges(nas = listOf(row("AAA", "100")), nys = listOf(row("BBB", "300")), ams = listOf(row("CCC", "200")))

            service.candidates(0.0).map { it.rank to it.symbol } shouldBe listOf(1 to "BBB", 2 to "CCC", 3 to "AAA")
        }

        @Test
        fun `ETF는 풀에서 제외하되 순위는 ETF를 포함해 매긴다`() {
            exchanges(nas = listOf(row("SPY", "900", ename = "SPDR S&P 500"), row("AAA", "100")))

            service.candidates(0.0).map { it.rank to it.symbol } shouldBe listOf(2 to "AAA")
        }

        @Test
        fun `거래대금 1위여도 등락률에 미달하면 뺀다`() {
            exchanges(nas = listOf(row("A", "500", "-9.0"), row("B", "400", "-8.0"), row("C", "300", "-7.0"), row("D", "200", "-6.0"), row("E", "100", "9.0")))

            service.candidates(5.0).map { it.symbol } shouldBe listOf("E")
        }

        @Test
        fun `기준에 미달하면 전부 빠져 빈 목록이 된다`() {
            exchanges(nas = "ABCDE".mapIndexed { i, s -> row("$s", "${600 - i * 100}", "1.0") })

            service.candidates(5.0).shouldBeEmpty()
        }

        @Test
        fun `등락 방향은 sign으로 대비값에 부호를 준다`() {
            exchanges(nas = listOf(row("A", "100", sign = "5")))

            service.candidates(0.0)[0].diff shouldBe -5.0
        }
    }

    @Nested
    inner class 시가총액_표기 {
        @Test
        fun `1조 이상은 T 그 아래는 B로 적는다`() {
            mapOf(4_509_899_684_000L to "$4.51T", 1_000_000_000_000L to "$1.00T", 943_046_066_300L to "$943B", 2_000_000_000L to "$2B")
                .forEach { (usd, expected) -> OverseasLeadingStockService.formatUsdCap(usd) shouldBe expected }
        }
    }

    @Nested
    inner class 종목_상세 {
        @Test
        fun `필터 다섯 개를 평가해 돌려준다`() {
            exchanges(nas = listOf(row("AAA", "100", "10.0")))
            every { product.fetchMarketCap("NAS", "AAA") } returns 100_000_000_000
            every { minutes.minuteCandles("NAS", "AAA") } returns emptyList()

            val filters = service.evaluate("NAS", "AAA").filters

            filters.map { it.filterName } shouldBe listOf("거래대금순위", "당일 등락률", "시가총액", "시가 대비 현재가", "시초가")
            filters.map { it.passed } shouldBe listOf(true, true, true, false, false)
            filters.drop(3).map { it.actualValue } shouldBe listOf("시가 없음", "시초가 없음")
            filters[1].actualValue shouldBe "+10.00%"
            filters[2].actualValue shouldBe "$100B"
            (filters[1].value to filters[1].threshold) shouldBe (10.0 to OverseasLeadingStockService.MIN_CHANGE_RATE_PCT)
            filters[2].value.shouldBeNull()
        }

        @Test
        fun `시가는 장전 거래를 건너뛴 정규장 첫 봉에서 읽는다`() {
            exchanges(nas = listOf(row("AAA", "100", "5.26")))
            every { product.fetchMarketCap("NAS", "AAA") } returns 100_000_000_000
            val day = LocalDate.of(2026, 10, 1)
            every { minutes.minuteCandles("NAS", "AAA") } returns listOf(
                OverseasMinuteCandle(LocalDateTime.of(2026, 10, 1, 22, 29), day, 99.0, 99.0, 99.0, 99.0, 1, 1.0),
                OverseasMinuteCandle(LocalDateTime.of(2026, 10, 1, 22, 30), day, 101.0, 101.0, 101.0, 101.0, 1, 1.0),
            )

            val (open, gap) = service.evaluate("NAS", "AAA").filters.drop(3)

            (open.actualValue to open.passed) shouldBe ("-0.99%" to false)
            (gap.actualValue to gap.passed) shouldBe ("$101.00 (+6.32%)" to true)
            gap.threshold shouldBe OverseasLeadingStockService.MAX_OPENING_GAP_PCT
        }

        @Test
        fun `시가총액을 못 구하면 조회 불가로 적고 탈락시킨다`() {
            exchanges(nas = listOf(row("AAA", "100")))
            every { product.fetchMarketCap("NAS", "AAA") } returns null
            every { minutes.minuteCandles("NAS", "AAA") } returns emptyList()

            val marketCap = service.evaluate("NAS", "AAA").filters[2]

            marketCap.actualValue shouldBe "조회 불가"
            marketCap.passed shouldBe false
        }

        @Test
        fun `분봉을 못 받아도 나머지 조건은 보여 준다`() {
            exchanges(nas = listOf(row("AAA", "100")))
            every { product.fetchMarketCap("NAS", "AAA") } returns null
            every { minutes.minuteCandles("NAS", "AAA") } throws IllegalStateException("KIS 오류")

            service.evaluate("NAS", "AAA").filters[3].actualValue shouldBe "시가 없음"
        }

        @Test
        fun `후보에 없는 종목은 조회할 수 없다`() {
            exchanges(nas = listOf(row("AAA", "100")))

            shouldThrow<EntityNotFoundException> { service.evaluate("NAS", "ZZZ") }.message shouldContain "후보에 없는 종목"
        }
    }

    @Nested
    inner class 후보_풀_보관_기간 {
        @Test
        fun `장중에는 20초만 들고 있는다`() {
            service.poolTtl() shouldBe Duration.ofSeconds(20)
        }

        @Test
        fun `장이 멈춘 동안은 다음 미국장까지 들고 있는다`() {
            usStatus = false to false

            service.poolTtl() shouldBe Duration.ofHours(7)
        }

        @Test
        fun `미국 휴장일에는 장 시간이어도 다음 미국장까지 들고 있는다`() {
            usStatus = true to true

            service.poolTtl() shouldBe Duration.ofHours(7)
        }

        @Test
        fun `후보와 주도주와 상세가 세 거래소 조회를 한 번 나눠 쓴다`() {
            exchanges(nas = listOf(row("AAA", "1000")))

            service.candidates(0.0)
            service.candidates(5.0)
            service.leaders(5)

            verify(exactly = 1) { ranking.fetchTradingValueRanking("NAS") }
        }
    }
}
