package judozoo.market.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.library.KST
import judozoo.market.domain.FuturesExpiry
import judozoo.market.domain.FuturesSession
import judozoo.market.domain.Indicator
import judozoo.platform.kis.KisFutures
import judozoo.platform.kis.KisFutures.FuturesDaily
import judozoo.platform.kis.KisFutures.FuturesPrice
import judozoo.platform.kis.KisFutures.FuturesSummary
import judozoo.platform.kis.KisFutures.NearMonth
import judozoo.platform.kiwoom.KiwoomIndex
import judozoo.platform.yahoo.YahooClient
import judozoo.stock.domain.Market
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private fun MarketQuotes.refresher(name: String) = refreshers().single { it.name == name }

class MarketQuotesTest {
    private val kiwoomIndex = mockk<KiwoomIndex>()
    private val futures = mockk<KisFutures>()
    private val yahoo = mockk<YahooClient>()
    private var now = LocalDateTime.of(2026, 9, 30, 10, 0)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val quotes = MarketQuotes(kiwoomIndex, futures, FuturesContracts(futures, clock), yahoo, clock)

    private fun summary(price: Double = 300.0) = FuturesSummary("K200 선물", price, 1.0, 299.0, 1.0, 0.0, 100, 1)
    private fun price(p: Double = 300.0) = FuturesPrice("K200 선물", p, p - 1, 1.0, 1.0, p - 1, p + 1, p - 2, 100, 100, 1)

    @Nested
    inner class 시세_폴러 {
        @Test
        fun `사용자가 없어도 채우고 들고 있는 값이 있으면 장 밖에서는 다시 받지 않는다`() {
            every { kiwoomIndex.fetchIndex(any()) } returns KiwoomIndex.IndexSnapshot("001", 2700.0, 1.5)
            val kospi = quotes.refresher("KOSPI")

            kospi.run { false } shouldBe true
            kospi.run { false }
            kospi.run { true }

            quotes.index(Market.KOSPI).currentValue shouldBe 2700.0
            verify(exactly = 2) { kiwoomIndex.fetchIndex(Market.KOSPI) }
        }

        @Test
        fun `갱신이 실패하면 마지막 정상값을 유지한다`() {
            every { kiwoomIndex.fetchIndex(any()) } returns KiwoomIndex.IndexSnapshot("001", 2700.0, 1.5)
            quotes.refresher("KOSPI").run { true }
            every { kiwoomIndex.fetchIndex(any()) } returns null

            shouldThrow<IllegalStateException> { quotes.refresher("KOSPI").run { true } }

            quotes.index(Market.KOSPI).currentValue shouldBe 2700.0
        }

        @Test
        fun `매크로 한 항목이 실패해도 나머지는 갱신한다`() {
            every { yahoo.fetchQuote(any()) } returns YahooClient.YahooQuote("", 100.0, 80.0)
            quotes.refreshers().filter { it.name in Indicator.MACRO.map { m -> m.symbol } }.forEach { it.run { true } }
            every { yahoo.fetchQuote(any()) } answers { if (firstArg<String>() == "KRW=X") null else YahooClient.YahooQuote("", 120.0, 80.0) }

            quotes.refreshers().filter { it.name in Indicator.MACRO.map { m -> m.symbol } }.forEach { it.run { true } }

            val macro = quotes.macroQuotes()
            macro.usdKrw!!.price shouldBe 100.0
            macro.wti!!.price shouldBe 120.0
            macro.wti!!.changeRate shouldBe 50.0
        }

        @Test
        fun `선물은 실패해도 이전 값을 유지한다`() {
            every { futures.fetchNearMonth(Market.KOSPI) } returns NearMonth("101", "F 202609", 1)
            every { futures.fetchDaily("101", any(), any(), any()) } returns FuturesDaily(summary(), emptyList())
            quotes.futuresQuote(Market.KOSPI)!!.futuresPrice shouldBe 300.0
            every { futures.fetchDaily("101", any(), any(), any()) } returns null

            quotes.refresher("futures-KOSPI").run { true } shouldBe false

            quotes.futuresQuote(Market.KOSPI)!!.futuresPrice shouldBe 300.0
            verify(exactly = 0) { futures.fetchInvestors(any()) }
        }
    }

    @Nested
    inner class 선물_근월물_공유 {
        @Test
        fun `정규장과 야간 시세가 근월물 조회 한 번을 공유한다`() {
            every { futures.fetchNearMonth(Market.KOSPI) } returns NearMonth("CONTRACT", "F 202612", 50)
            every { futures.fetchDaily("CONTRACT", any(), any(), any()) } returns FuturesDaily(summary(), emptyList())
            every { futures.fetchPrice("CONTRACT", FuturesSession.NIGHT) } returns price()

            quotes.futuresQuote(Market.KOSPI)!!.futuresPrice shouldBe 300.0
            quotes.nightFuturesQuote()!!.price shouldBe 300.0
            quotes.refresher("futures-KOSPI").run { true }

            verify(exactly = 1) { futures.fetchNearMonth(Market.KOSPI) }
        }

        @Test
        fun `만기 교체로 옛 코드가 실패하면 근월물을 다시 확인한다`() {
            now = LocalDateTime.of(2026, 9, 30, 17, 59)
            every { futures.fetchNearMonth(Market.KOSPI) } returnsMany listOf(NearMonth("OLD", "F 202609", 0), NearMonth("NEW", "F 202612", 90))
            every { futures.fetchPrice("OLD", FuturesSession.NIGHT) } returns null
            every { futures.fetchPrice("NEW", FuturesSession.NIGHT) } returns price(310.0)

            quotes.nightFuturesQuote()!!.price shouldBe 310.0
            verify(exactly = 2) { futures.fetchNearMonth(Market.KOSPI) }
        }

        @Test
        fun `야간장이 시작하면 근월물을 다시 확인한다`() {
            now = LocalDateTime.of(2026, 9, 30, 17, 59)
            every { futures.fetchNearMonth(Market.KOSPI) } returns NearMonth("CONTRACT", "F 202612", 90)
            every { futures.fetchPrice(any(), any()) } returns price()
            quotes.nightFuturesQuote()

            now = LocalDateTime.of(2026, 9, 30, 18, 0)
            quotes.refresher("night").run { true }

            verify(exactly = 2) { futures.fetchNearMonth(Market.KOSPI) }
        }

        @Test
        fun `자정이 지나도 진행 중인 야간 세션 코드를 재조회하지 않는다`() {
            now = LocalDateTime.of(2026, 9, 30, 23, 0)
            every { futures.fetchNearMonth(Market.KOSPI) } returns NearMonth("CONTRACT", "F 202612", 90)
            every { futures.fetchPrice(any(), any()) } returns price()
            quotes.nightFuturesQuote()

            now = LocalDateTime.of(2026, 10, 1, 1, 0)
            quotes.refresher("night").run { true }

            verify(exactly = 1) { futures.fetchNearMonth(Market.KOSPI) }
        }
    }

    @Nested
    inner class 만기일 {
        @Test
        fun `만기월 둘째 목요일이 최종거래일이다`() {
            FuturesExpiry.of("F 202609") shouldBe LocalDate.of(2026, 9, 10)
            FuturesExpiry.of("F 202610") shouldBe LocalDate.of(2026, 10, 8)
        }

        @Test
        fun `이름에서 월을 못 읽으면 모른다`() {
            FuturesExpiry.of("K200 선물").shouldBeNull()
            FuturesExpiry.of("F 202613").shouldBeNull()
        }

        @Test
        fun `시세에 만기일을 싣는다`() {
            every { futures.fetchNearMonth(Market.KOSPI) } returns NearMonth("101", "F 202610", 8)
            every { futures.fetchDaily("101", any(), any(), any()) } returns FuturesDaily(summary(), emptyList())

            quotes.futuresQuote(Market.KOSPI)!!.expiryDate shouldBe "2026-10-08"
        }
    }
}
