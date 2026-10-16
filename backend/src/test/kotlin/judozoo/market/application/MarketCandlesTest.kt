package judozoo.market.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.IntegrationTest
import judozoo.library.KST
import judozoo.market.domain.Region
import judozoo.market.infrastructure.FuturesMinuteArchive
import judozoo.market.infrastructure.IndexMinuteArchive
import judozoo.market.infrastructure.YahooMinuteArchive
import judozoo.platform.kis.KisFutures
import judozoo.platform.kis.KisFutures.FuturesBar
import judozoo.platform.kis.KisFutures.NearMonth
import judozoo.platform.toss.TossMarketIndicator
import judozoo.platform.toss.TossMarketIndicator.CandlesPage
import judozoo.platform.toss.TossMarketIndicator.TossCandle
import judozoo.platform.yahoo.YahooClient
import judozoo.platform.yahoo.YahooClient.YahooBar
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@IntegrationTest
class MarketCandlesTest @Autowired constructor(
    private val indexArchive: IndexMinuteArchive,
    private val futuresArchive: FuturesMinuteArchive,
    private val yahooArchive: YahooMinuteArchive,
    private val jdbc: JdbcTemplate,
) {
    private val toss = mockk<TossMarketIndicator>()
    private val futures = mockk<KisFutures>()
    private val yahoo = mockk<YahooClient>()
    private val calendar = mockk<MarketCalendar> {
        every { isOpen(any()) } returns true
        every { previousOpenDay(any()) } returns null
    }
    private var now = LocalDateTime.of(2026, 9, 29, 10, 0)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }

    private fun fresh() = MarketCandles(toss, futures, FuturesContracts(futures, clock), yahoo, calendar, indexArchive, futuresArchive, yahooArchive, clock)

    @BeforeEach
    fun 빈_테이블() {
        listOf("index_minute_day", "futures_minute_day", "yahoo_minute_day").forEach { jdbc.update("DELETE FROM $it") }
        every { futures.fetchNearMonth(any()) } returns NearMonth("CONTRACT", "F 202612", 50)
    }

    private fun candle(at: LocalDateTime) = TossCandle(at, 1.0, 1.0, 1.0, 1.0, 1.0)
    private fun bar(day: LocalDate, time: String) = FuturesBar(day.toString(), time, 1.0, 2.0, 0.5, 1.5, 10.0)
    private fun yahooBar(day: LocalDate, time: String) = YahooBar(day.toString(), time, 1.0, 2.0, 0.5, 1.5, 10.0)

    @Nested
    inner class 지수_캔들 {
        @Test
        fun `1분봉을 다시 물으면 토스를 부르지 않고 지수가 다르면 따로 받는다`() {
            every { toss.fetchCandles(any(), "1m", any(), any()) } returns CandlesPage(listOf(candle(now)), null)
            val candles = fresh()

            candles.indexMinute(Market.KOSPI)
            candles.indexMinute(Market.KOSPI)
            candles.indexMinute(Market.KOSDAQ)

            verify(exactly = 1) { toss.fetchCandles("KOSPI", "1m", any(), any()) }
            verify(exactly = 1) { toss.fetchCandles("KOSDAQ", "1m", any(), any()) }
        }

        @Test
        fun `캐시를 비워도 직전일은 DB에서 복원하고 페이지를 덜 받는다`() {
            val today = candle(LocalDateTime.of(2026, 9, 29, 9, 0))
            val yesterday = listOf(candle(LocalDateTime.of(2026, 9, 28, 9, 0)), candle(LocalDateTime.of(2026, 9, 28, 9, 1)))
            val earlier = candle(LocalDateTime.of(2026, 9, 25, 15, 0))
            every { toss.fetchCandles("KOSPI", "1m", any(), null) } returns CandlesPage(listOf(today, yesterday[1]), "older")
            every { toss.fetchCandles("KOSPI", "1m", any(), "older") } returns CandlesPage(listOf(yesterday[0], earlier), "more")

            fresh().indexMinute(Market.KOSPI) shouldBe yesterday + today
            indexArchive.get(Market.KOSPI, LocalDate.of(2026, 9, 28)) shouldBe yesterday
            indexArchive.get(Market.KOSPI, LocalDate.of(2026, 9, 29)).shouldBeNull()
            indexArchive.get(Market.KOSPI, LocalDate.of(2026, 9, 25)).shouldBeNull()

            clearMocks(toss, answers = false)
            fresh().indexMinute(Market.KOSPI) shouldBe yesterday + today
            verify(exactly = 1) { toss.fetchCandles(any(), any(), any(), any()) }
            indexArchive.get(Market.KOSDAQ, LocalDate.of(2026, 9, 28)).shouldBeNull()
        }

        @Test
        fun `다음 페이지 실패로 잘린 날은 보관하지 않는다`() {
            val lastBar = candle(LocalDateTime.of(2026, 9, 28, 15, 0))
            every { toss.fetchCandles("KOSPI", "1m", any(), null) } returns CandlesPage(listOf(lastBar), "older")
            every { toss.fetchCandles("KOSPI", "1m", any(), "older") } returns CandlesPage(emptyList(), null)

            fresh().indexMinute(Market.KOSPI) shouldBe listOf(lastBar)
            indexArchive.get(Market.KOSPI, LocalDate.of(2026, 9, 28)).shouldBeNull()
        }

        @Test
        fun `휴일에도 최근 두 거래일을 보관한다`() {
            now = LocalDateTime.of(2026, 10, 3, 10, 0)
            val latest = candle(LocalDateTime.of(2026, 10, 2, 9, 0))
            val previous = candle(LocalDateTime.of(2026, 10, 1, 9, 0))
            every { toss.fetchCandles(any(), any(), any(), any()) } returns CandlesPage(listOf(latest, previous), null)

            fresh().indexMinute(Market.KOSPI) shouldBe listOf(previous, latest)
            indexArchive.get(Market.KOSPI, LocalDate.of(2026, 10, 2)) shouldBe listOf(latest)
            indexArchive.get(Market.KOSPI, LocalDate.of(2026, 10, 1)) shouldBe listOf(previous)
        }

        @Test
        fun `일봉도 다시 물으면 부르지 않는다`() {
            every { toss.fetchCandles(any(), "1d", any(), any()) } returns CandlesPage(listOf(candle(now)), null)
            val candles = fresh()

            candles.indexDaily(Market.KOSPI, 90)
            candles.indexDaily(Market.KOSPI, 90)

            verify(exactly = 1) { toss.fetchCandles("KOSPI", "1d", 90, null) }
        }
    }

    @Nested
    inner class 지난_거래일_선물_분봉 {
        private val current = LocalDate.of(2026, 9, 30)
        private val previous = LocalDate.of(2026, 9, 29)

        @ParameterizedTest
        @CsvSource("KOSPI", "KOSDAQ")
        fun `지난 거래일은 DB에서 재사용하고 오늘은 다시 조회한다`(market: Market) {
            now = current.atTime(13, 0)
            every { futures.fetchMinute("CONTRACT", any(), any<LocalTime>(), any()) } answers { listOf(bar(secondArg(), "08:45:00"), bar(secondArg(), "09:00:00")) }

            fresh().futuresMinute(market) shouldBe listOf(bar(previous, "08:45:00"), bar(previous, "09:00:00"), bar(current, "08:45:00"), bar(current, "09:00:00"))
            futuresArchive.get(market, "CONTRACT", false, previous).shouldNotBeNull()
            futuresArchive.get(market, "CONTRACT", false, current).shouldBeNull()
            futuresArchive.get(market, "OTHER", false, previous).shouldBeNull()

            clearMocks(futures, answers = false)
            fresh().futuresMinute(market)
            verify(exactly = 1) { futures.fetchMinute("CONTRACT", current, any<LocalTime>(), any()) }
            verify(exactly = 0) { futures.fetchMinute("CONTRACT", previous, any<LocalTime>(), any()) }
        }

        @Test
        fun `잘린 지난날 페이지는 보관하지 않는다`() {
            now = current.atTime(13, 0)
            every { futures.fetchMinute("CONTRACT", any(), any<LocalTime>(), any()) } answers {
                if (secondArg<LocalDate>() == previous && thirdArg<LocalTime>().hour == 15) List(102) { bar(previous, "09:30:00") } else emptyList()
            }

            fresh().futuresMinute(Market.KOSPI)

            futuresArchive.get(Market.KOSPI, "CONTRACT", false, previous).shouldBeNull()
        }

        @Test
        fun `전날 야간 세션은 DB에서 복원한다`() {
            now = current.atTime(14, 0)
            every { futures.fetchMinute("CONTRACT", any(), any<String>(), KisFutures.NIGHT) } returns listOf(bar(previous, "18:00:00"), bar(current, "05:59:00"))

            fresh().nightMinute() shouldBe listOf(bar(previous, "18:00:00"), bar(current, "05:59:00"))
            futuresArchive.get(Market.KOSPI, "CONTRACT", true, previous).shouldNotBeNull()
            futuresArchive.get(Market.KOSPI, "CONTRACT", false, previous).shouldBeNull()

            clearMocks(futures, answers = false)
            fresh().nightMinute()
            verify(exactly = 0) { futures.fetchMinute(any(), any(), any<String>(), any()) }
        }

        @Test
        fun `진행 중인 야간 세션은 DB에 저장하지 않는다`() {
            now = current.atTime(19, 0)
            every { futures.fetchMinute("CONTRACT", any(), any<String>(), KisFutures.NIGHT) } returns listOf(bar(current, "18:00:00"), bar(current, "19:00:00"))

            fresh().nightMinute()

            futuresArchive.get(Market.KOSPI, "CONTRACT", true, current).shouldBeNull()
        }

        @Test
        fun `자정 이후에도 전날 시작한 야간 세션으로 묶는다`() {
            now = current.atTime(2, 0)
            every { futures.fetchMinute("CONTRACT", any(), any<String>(), KisFutures.NIGHT) } returns listOf(bar(previous, "18:00:00"), bar(current, "02:00:00"))

            fresh().nightMinute() shouldBe listOf(bar(previous, "18:00:00"), bar(current, "02:00:00"))
            verify { futures.fetchMinute("CONTRACT", previous, "295900", KisFutures.NIGHT) }
            futuresArchive.get(Market.KOSPI, "CONTRACT", true, previous).shouldBeNull()
        }
    }

    @Nested
    inner class 야후_지난_거래_세션 {
        private fun usBar(at: LocalDateTime): YahooBar {
            val kst = at.atZone(Region.US.zone).withZoneSameInstant(KST)
            return yahooBar(kst.toLocalDate(), kst.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
        }

        private fun usBar(day: LocalDate, clock: String) = usBar(LocalDateTime.parse("${day}T$clock"))

        @ParameterizedTest
        @CsvSource(
            "USD_KRW, KRW=X, 2026-09-28T17:00:00, 2026-09-29T16:59:00, 2026-09-29T17:00:00, 2026-09-30T14:00:00",
            "WTI, CL=F, 2026-09-28T18:00:00, 2026-09-29T16:59:00, 2026-09-29T18:00:00, 2026-09-30T14:00:00",
            "VIX, ^VIX, 2026-09-29T09:30:00, 2026-09-29T15:59:00, 2026-09-30T09:30:00, 2026-09-30T14:00:00",
            "US10Y, ^TNX, 2026-09-29T08:20:00, 2026-09-29T15:00:00, 2026-09-30T08:20:00, 2026-09-30T14:00:00",
        )
        fun `지난 세션은 저장하고 최신 세션만 다시 조회한다`(target: String, symbol: String, p1: String, p2: String, c1: String, c2: String) {
            now = LocalDateTime.of(2026, 10, 1, 3, 0)
            val previous = listOf(usBar(LocalDateTime.parse(p1)), usBar(LocalDateTime.parse(p2)))
            val current = listOf(usBar(LocalDateTime.parse(c1)), usBar(LocalDateTime.parse(c2)))
            every { yahoo.fetchCandles(symbol, "1m", any()) } answers { if (thirdArg<String>() == "1d") current else previous + current }

            val candles = fresh()
            candles.macro(target, "1m") shouldBe previous + current
            yahooArchive.recent(symbol, LocalDate.of(2026, 9, 30)) shouldBe mapOf(LocalDate.of(2026, 9, 29) to previous)
            candles.macro(target, "1m") shouldBe previous + current
            verify(exactly = 1) { yahoo.fetchCandles(symbol, "1m", "2d") }

            fresh().macro(target, "1m") shouldBe previous + current
            verify(exactly = 1) { yahoo.fetchCandles(symbol, "1m", "1d") }
            (LocalDate.of(2026, 9, 30) in yahooArchive.recent(symbol, LocalDate.of(2026, 9, 30))) shouldBe false
        }

        @ParameterizedTest
        @CsvSource(
            "USD_KRW, KRW=X, 10:00:00, 16:59:00",
            "WTI, CL=F, 10:00:00, 16:59:00",
            "VIX, ^VIX, 11:00:00, 15:59:00",
            "US10Y, ^TNX, 11:00:00, 15:00:00",
        )
        fun `앞부분이 잘린 세션은 보관하지 않는다`(target: String, symbol: String, from: String, to: String) {
            now = LocalDateTime.of(2026, 10, 1, 3, 0)
            val day = LocalDate.of(2026, 9, 29)
            every { yahoo.fetchCandles(symbol, "1m", any()) } returns listOf(usBar(day, from), usBar(day, to))

            fresh().macro(target, "1m")

            yahooArchive.recent(symbol, LocalDate.of(2026, 9, 30)) shouldBe emptyMap()
        }

        @Test
        fun `나스닥은 한국시간 자정을 넘어도 한 미국 거래일로 보관하고 재사용한다`() {
            now = LocalDateTime.of(2026, 10, 1, 3, 0)
            val previous = listOf(yahooBar(LocalDate.of(2026, 9, 29), "22:30:00"), yahooBar(LocalDate.of(2026, 9, 30), "04:59:00"))
            val current = listOf(yahooBar(LocalDate.of(2026, 9, 30), "22:30:00"), yahooBar(LocalDate.of(2026, 10, 1), "03:00:00"))
            every { yahoo.fetchCandles("^IXIC", "1m", any()) } answers { if (thirdArg<String>() == "1d") current else previous + current }

            fresh().nasdaq("1m") shouldBe previous + current
            yahooArchive.recent("^IXIC", LocalDate.of(2026, 9, 30)) shouldBe mapOf(LocalDate.of(2026, 9, 29) to previous)

            fresh().nasdaq("1m") shouldBe previous + current
            verify(exactly = 1) { yahoo.fetchCandles("^IXIC", "1m", "2d") }
            verify(exactly = 1) { yahoo.fetchCandles("^IXIC", "1m", "1d") }
        }

        @Test
        fun `나스닥의 잘린 지난날은 DB에 확정하지 않는다`() {
            now = LocalDateTime.of(2026, 10, 1, 3, 0)
            every { yahoo.fetchCandles("^IXIC", "1m", any()) } returns listOf(
                yahooBar(LocalDate.of(2026, 9, 30), "00:00:00"),
                yahooBar(LocalDate.of(2026, 9, 30), "04:59:00"),
            )

            fresh().nasdaq("1m")

            yahooArchive.recent("^IXIC", LocalDate.of(2026, 9, 30)).keys.shouldBeEmpty()
        }
    }
}
