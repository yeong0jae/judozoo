package judozoo.market.application

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.library.JobFailures
import judozoo.library.KST
import judozoo.market.domain.Region
import judozoo.platform.kiwoom.KiwoomIndex
import judozoo.platform.yahoo.YahooClient
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

class MarketQuoteSchedulerTest {
    private val calendar = mockk<MarketCalendar>()
    private val registry = SimpleMeterRegistry()
    private fun scheduler(at: LocalDateTime = LocalDateTime.of(2026, 9, 30, 10, 0), quotes: MarketQuotes = mockk()) =
        MarketQuoteScheduler(quotes, calendar, JobFailures(registry), Clock.fixed(at.atZone(KST).toInstant(), KST))

    @Nested
    inner class 시세_폴러 {
        private val kiwoomIndex = mockk<KiwoomIndex> { every { fetchIndex(any()) } returns KiwoomIndex.IndexSnapshot("001", 2700.0, 1.5) }
        private val yahoo = mockk<YahooClient> { every { fetchQuote(any()) } returns YahooClient.YahooQuote("", 100.0, 80.0) }
        private val clock = Clock.fixed(LocalDateTime.of(2026, 9, 30, 10, 0).atZone(KST).toInstant(), KST)
        private val futures = mockk<judozoo.platform.kis.KisFutures> { every { fetchNearMonth(any()) } returns null }
        private val quotes = MarketQuotes(kiwoomIndex, futures, FuturesContracts(futures, clock), yahoo, clock)

        @Test
        fun `장중에는 들고 있는 국내 시세도 매번 갱신한다`() {
            every { calendar.isHoliday(any()) } returns false
            every { calendar.isOpen(any()) } returns true

            scheduler(quotes = quotes).poll()
            scheduler(quotes = quotes).poll()

            quotes.index(judozoo.stock.domain.Market.KOSPI).currentValue shouldBe 2700.0
            verify(exactly = 4) { kiwoomIndex.fetchIndex(any()) }
        }

        @Test
        fun `빈 값을 받으면 실패로 센다`() {
            every { calendar.isHoliday(any()) } returns false
            every { calendar.isOpen(any()) } returns true

            scheduler(quotes = quotes).poll()

            (registry.find("scheduler.job.errors").tag("job_id", "market-quote-poller").counter()?.count() ?: 0.0) shouldBeGreaterThan 0.0
        }

        @Test
        fun `휴장에는 처음만 채우고 국내 시세를 다시 받지 않는다`() {
            every { calendar.isHoliday(any()) } returns true
            every { calendar.isOpen(any()) } returns false

            scheduler(quotes = quotes).poll()
            scheduler(quotes = quotes).poll()

            verify(exactly = 2) { kiwoomIndex.fetchIndex(any()) }
        }
    }

    @Nested
    inner class 시세_운영시간 {
        @Test
        fun `야간은 금요일 세션의 토요일 새벽도 포함한다`() {
            every { calendar.isOpen(LocalDate.of(2026, 10, 2)) } returns true

            scheduler().nightOpen(LocalDateTime.of(2026, 10, 3, 5, 0)) shouldBe true
            scheduler().nightOpen(LocalDateTime.of(2026, 10, 3, 12, 0)) shouldBe false
        }

        @Test
        fun `개장일 목록을 모르면 주말만 쉰다`() {
            every { calendar.isOpen(any()) } returns null

            scheduler().nightOpen(LocalDateTime.of(2026, 10, 2, 19, 0)) shouldBe true
            scheduler().nightOpen(LocalDateTime.of(2026, 10, 3, 19, 0)) shouldBe false
        }

        @ParameterizedTest
        @ValueSource(strings = ["2026-09-30T22:30", "2026-12-01T23:30"])
        fun `미국 정규장은 서머타임을 따른다`(at: String) {
            every { calendar.isHoliday(Region.US) } returns false

            scheduler().usCashOpen(LocalDateTime.parse(at)) shouldBe true
        }

        @Test
        fun `주말 글로벌 시세는 쉬고 일요일 뉴욕 저녁에 재개한다`() {
            scheduler().globalOpen(LocalDateTime.of(2026, 10, 4, 12, 0)) shouldBe false
            scheduler().globalOpen(LocalDateTime.of(2026, 10, 5, 7, 0)) shouldBe true
        }

        @Test
        fun `국내 시세는 휴장이면 장 시간이어도 쉰다`() {
            every { calendar.isHoliday(Region.KR) } returns true

            scheduler().domesticOpen(LocalDateTime.of(2026, 10, 3, 10, 0), java.time.LocalTime.of(9, 0), java.time.LocalTime.of(15, 42)) shouldBe false
        }
    }
}
