package judozoo.leadingstock.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.StockMinuteCandleEntity
import judozoo.leadingstock.domain.StockMinuteCandleRepository
import judozoo.library.EntityNotFoundException
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.MarketStatus
import judozoo.platform.kiwoom.KiwoomMarket
import judozoo.platform.kiwoom.KiwoomProgram
import judozoo.platform.toss.TossCandles
import judozoo.stock.application.StockCatalog
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LeadingStockServiceTest {
    private var now = LocalDateTime.of(2026, 9, 28, 10, 0)
    private var holiday = false
    private var openDays = listOf<LocalDate>()

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val kiwoom = mockk<KiwoomMarket>()
    private val program = mockk<KiwoomProgram> { every { fetchProgramNetBuy(any()) } returns 0 }
    private val tossCalls = mutableListOf<LocalDateTime?>()
    private var tossFails = false
    private var tossBars: () -> List<MinuteCandle> = { listOf(bar(now.toLocalDate(), 9)) }
    private val toss = mockk<TossCandles> {
        every { fetchTodayMinuteCandles(any(), any()) } answers {
            tossCalls += secondArg<LocalDateTime?>()
            if (tossFails) throw IllegalStateException("토스 오류")
            tossBars()
        }
    }

    private val repository = mockk<StockMinuteCandleRepository>(relaxed = true) {
        every { findByStockCodeAndTradeDateOrderByMinuteAt(any(), any()) } returns emptyList()
        every { saveAll(any<List<StockMinuteCandleEntity>>()) } answers { firstArg() }
    }
    private val archive = StockMinuteArchive(repository, TransactionTemplate(mockk<PlatformTransactionManager>(relaxed = true)))
    private val wideLimitDays = mockk<WideLimitDays> { every { mark(any(), any(), any()) } answers { firstArg() } }
    private val calendar = mockk<MarketCalendar> {
        every { marketStatus() } answers { MarketStatus(holiday, now.hour in 8..19) }
        every { previousOpenDay(any()) } answers { openDays.filter { it < firstArg<LocalDate>() }.maxOrNull() }
        every { isOpen(any()) } answers { !holiday }
    }
    private val catalog = mockk<StockCatalog> { every { marketOf(any()) } returns null }
    private val service = LeadingStockService(kiwoom, program, TodayMinutes(toss, clock), archive, wideLimitDays, calendar, catalog, clock)

    private fun stock(code: String = "005930", price: Long = 105, rate: Double = 5.0, volume: Long? = null) = LeadingStockSnapshot(
        stockCode = code, stockName = "삼성전자", currentPrice = price, priceChangeRate = rate, tradingValueRank = 1,
        accumulatedTradingValue = 1_000_000_000_000, accumulatedVolume = volume,
    )

    private fun bar(day: LocalDate, hour: Int, minute: Int = 0) = MinuteCandle(day.atTime(hour, minute), 100, 100, 100, 100, 1, 100)

    private fun pool(vararg stocks: LeadingStockSnapshot) {
        every { kiwoom.fetchTopTradingValueStocks(any()) } returns stocks.toList()
    }

    @Nested
    inner class 주도주_상세_시세 {
        private val today = LocalDate.of(2026, 9, 30)

        init {
            now = LocalDateTime.of(2026, 9, 30, 10, 0)
            every { kiwoom.fetchStockDetail("005930") } returns LeadingStockSnapshot(
                "005930", "삼성전자", 999, 99.0, 0, 0, marketCap = 5000, openingPrice = 100, previousClose = 100,
            )
            every { kiwoom.fetchDailyCandles("005930", 61) } returns listOf(
                DailyCandle(today, 100, 200, 90, 105, 1000, 5.0),
                DailyCandle(today.minusDays(1), 95, 110, 90, 100, 1000, 1.0),
            )
        }

        @Test
        fun `후보 풀 시세에 기본정보의 시가와 시총을 얹고 기본정보는 하루에 한 번만 조회한다`() {
            pool(stock(volume = 2000))

            val first = service.evaluate("005930")
            service.evaluate("005930")

            (first.stock.currentPrice to first.stock.priceChangeRate) shouldBe (105L to 5.0)
            (first.stock.openingPrice to first.stock.marketCap) shouldBe (100L to 5000L)
            first.relativeVolume shouldBe 2.0
            first.filterResults.first { it.filterName == "시가 대비 현재가" }.passed shouldBe true
            first.filterResults.first { it.filterName == "최근 고가 대비 현재가" }.passed shouldBe true
            verify(exactly = 1) { kiwoom.fetchStockDetail("005930") }
            verify(exactly = 1) { kiwoom.fetchDailyCandles("005930", 61) }
        }

        @Test
        fun `풀이 갱신되면 새 시세로 상대거래량을 다시 낸다`() {
            pool(stock(volume = 2000))
            service.evaluate("005930")
            pool(stock(price = 109, volume = 3000))
            service.refreshTradingValuePool()

            val updated = service.evaluate("005930")

            updated.relativeVolume shouldBe 3.0
            updated.stock.currentPrice shouldBe 109
            verify(exactly = 1) { kiwoom.fetchDailyCandles("005930", 61) }
        }

        @Test
        fun `휴장일의 누적 거래량으로는 상대거래량을 내지 않는다`() {
            pool(stock(volume = 2000))
            holiday = true

            service.evaluate("005930").relativeVolume.shouldBeNull()
        }

        @Test
        fun `날이 바뀌면 기본정보와 일봉을 다시 받는다`() {
            pool(stock(volume = 2000))
            service.evaluate("005930")
            now = now.plusDays(1)
            every { kiwoom.fetchDailyCandles("005930", 61) } returns listOf(DailyCandle(today, 95, 110, 90, 100, 1000, 1.0))

            service.evaluate("005930")

            verify(exactly = 2) { kiwoom.fetchStockDetail("005930") }
            verify(exactly = 2) { kiwoom.fetchDailyCandles("005930", 61) }
        }

        @Test
        fun `후보 풀 밖의 종목은 기본정보 시세를 쓴다`() {
            pool()

            service.evaluate("005930").stock.currentPrice shouldBe 999
        }

        @Test
        fun `후보 풀에도 없고 시세도 없는 종목은 찾을 수 없다`() {
            pool()
            every { kiwoom.fetchStockDetail("999999") } returns null
            every { kiwoom.fetchDailyCandles("999999", 61) } returns emptyList()

            shouldThrow<EntityNotFoundException> { service.evaluate("999999") }
        }

        @Test
        fun `프로그램 매매는 상세 한 번에 한 번만 조회한다`() {
            pool()
            every { program.fetchProgramNetBuy("005930") } returns 500

            service.evaluate("005930").filterResults.first { it.filterName == "프로그램 양매수" }.passed shouldBe true
            verify(exactly = 1) { program.fetchProgramNetBuy("005930") }
        }

        @Test
        fun `조건은 판별력이 큰 것부터 나열한다`() {
            pool(stock())

            service.evaluate("005930").filterResults.map { it.filterName } shouldBe listOf(
                "거래대금순위", "당일등락률", "최근 고가 대비 현재가", "시가 대비 현재가", "전일 등락률", "시초가", "시가총액", "프로그램 양매수",
            )
        }
    }

    @Nested
    inner class 거래대금_상위_보관_기간 {
        @Test
        fun `장중에는 20초만 들고 있는다`() {
            now = LocalDateTime.of(2026, 9, 25, 10, 0)

            service.poolTtl() shouldBe Duration.ofSeconds(20)
        }

        @Test
        fun `마감 뒤에 받은 값은 다음날 장 시작까지 간다`() {
            now = LocalDateTime.of(2026, 9, 25, 20, 30)

            service.poolTtl() shouldBe Duration.ofMinutes(11 * 60 + 30)
        }

        @Test
        fun `새벽에 받은 값은 그날 장 시작까지 간다`() {
            now = LocalDateTime.of(2026, 9, 25, 6, 0)

            service.poolTtl() shouldBe Duration.ofHours(2)
        }

        @Test
        fun `휴장일에는 낮에 받아도 다음날 장 시작까지 간다`() {
            now = LocalDateTime.of(2026, 9, 24, 17, 10)
            holiday = true

            service.poolTtl() shouldBe Duration.ofMinutes(14 * 60 + 50)
        }

        @Test
        fun `장이 멈춘 동안은 한 번 받은 거래대금 상위를 다시 부르지 않는다`() {
            now = LocalDateTime.of(2026, 9, 25, 21, 0)
            pool(stock())

            listOf(0.0, -12.0, 5.0).forEach { service.findCandidates(it) }

            verify(exactly = 1) { kiwoom.fetchTopTradingValueStocks(any()) }
        }
    }

    @Nested
    inner class 상장_첫날_표시의_거래일 {
        @Test
        fun `자정 넘어 받은 순위는 직전 거래일 것이다`() {
            now = LocalDateTime.of(2026, 10, 2, 0, 23)
            openDays = listOf(LocalDate.of(2026, 10, 1))
            pool(stock())

            service.findCandidates(0.0)

            verify { wideLimitDays.mark(any(), LocalDate.of(2026, 10, 1), now) }
        }

        @Test
        fun `장중에 받은 순위는 오늘 것이다`() {
            now = LocalDateTime.of(2026, 10, 2, 10, 0)
            openDays = listOf(LocalDate.of(2026, 10, 1))
            pool(stock())

            service.findCandidates(0.0)

            verify { wideLimitDays.mark(any(), LocalDate.of(2026, 10, 2), now) }
        }

        @Test
        fun `휴장일에 받은 순위는 직전 거래일 것이다`() {
            now = LocalDateTime.of(2026, 10, 3, 14, 0)
            holiday = true
            openDays = listOf(LocalDate.of(2026, 10, 2))
            pool(stock())

            service.findCandidates(0.0)

            verify { wideLimitDays.mark(any(), LocalDate.of(2026, 10, 2), now) }
        }
    }

    @Nested
    inner class 당일_분봉_읽기 {
        private val today = LocalDate.of(2026, 9, 28)

        init {
            openDays = listOf(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25))
            every { kiwoom.fetchHistoricalMinuteCandles(any(), any()) } answers { listOf(bar(secondArg(), 9)) }
        }

        private fun read() = service.minuteCandles("035720", today).filter { it.dateTime.toLocalDate() == today }

        @Test
        fun `장중 30초 안에 받은 값이면 다시 부르지 않는다`() {
            now = today.atTime(14, 0)
            read()
            now = now.plusSeconds(20)

            read()

            tossCalls.size shouldBe 1
        }

        @Test
        fun `장중 30초가 지나면 새 봉만 받는다`() {
            now = today.atTime(14, 0)
            read()
            now = now.plusSeconds(40)

            read()

            tossCalls[0].shouldBeNull()
            tossCalls[1] shouldBe today.atTime(8, 58)
        }

        @Test
        fun `마감이 굳은 뒤 한 번 받으면 다음 장까지 다시 부르지 않는다`() {
            now = today.atTime(21, 0)
            read()
            now = today.atTime(23, 59)

            read()

            tossCalls.size shouldBe 1
        }

        @Test
        fun `마감 전에 받은 값은 마감이 굳은 뒤 다시 받는다`() {
            now = today.atTime(20, 0, 30)
            read()
            now = today.atTime(20, 2)

            read()

            tossCalls.size shouldBe 2
        }

        @Test
        fun `마감 뒤 받다가 실패한 옛 봉은 확정본으로 넘기지 않는다`() {
            now = today.atTime(19, 0)
            read()
            now = today.atTime(21, 0)
            tossFails = true

            read().shouldNotBeEmpty()
            archive.get("035720", today).shouldBeNull()
        }

        @Test
        fun `오늘 확정본이 보관소에 있으면 토스를 부르지 않는다`() {
            now = today.atTime(20, 30)
            archive.put("035720", today, listOf(bar(today, 19, 59)))

            read().map { it.dateTime } shouldBe listOf(today.atTime(19, 59))
            tossCalls.shouldBeEmpty()
        }

        @Test
        fun `휴장일에는 토스를 부르지 않는다`() {
            now = today.atTime(14, 0)
            holiday = true

            read().shouldBeEmpty()
            tossCalls.shouldBeEmpty()
        }

        @Test
        fun `장 시작 전에는 토스를 부르지 않는다`() {
            now = today.atTime(7, 30)

            read().shouldBeEmpty()
            tossCalls.shouldBeEmpty()
        }
    }

    @Nested
    inner class 최근_3거래일_분봉_달력으로 {
        private val today = LocalDate.of(2026, 9, 28)
        private val bases = mutableListOf<LocalDate>()

        init {
            now = today.atTime(10, 0)
            openDays = listOf(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 25))
            every { kiwoom.fetchHistoricalMinuteCandles(any(), any()) } answers {
                val base = secondArg<LocalDate>()
                bases += base
                listOf(bar(base, 9)) + listOfNotNull(openDays.filter { it < base }.maxOrNull()?.let { bar(it, 19) })
            }
        }

        private fun days(on: LocalDate = today) = service.minuteCandles("005930", on).map { it.dateTime.toLocalDate() }.distinct()

        @Test
        fun `오늘과 달력의 직전 두 거래일을 잇는다`() {
            days() shouldBe listOf(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 25), today)
        }

        @Test
        fun `보관소에 있는 날은 키움을 부르지 않는다`() {
            archive.put("005930", LocalDate.of(2026, 9, 25), listOf(bar(LocalDate.of(2026, 9, 25), 9)))
            archive.put("005930", LocalDate.of(2026, 9, 23), listOf(bar(LocalDate.of(2026, 9, 23), 9)))

            days()

            bases.shouldBeEmpty()
        }

        @Test
        fun `키움에서 받은 날은 보관소에 넣어 다음엔 부르지 않는다`() {
            days()
            val first = bases.toList()

            days()

            first shouldBe listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 23))
            bases shouldBe first
        }

        @Test
        fun `페이지 끝에서 잘린 날은 보관하지 않는다`() {
            days()

            archive.get("005930", LocalDate.of(2026, 9, 22)).shouldBeNull()
        }

        @Test
        fun `지난 날짜를 보면 그날부터 세 거래일이다`() {
            days(LocalDate.of(2026, 9, 25)) shouldBe openDays
        }
    }

    @Nested
    inner class 최근_3거래일_분봉_달력_없이 {
        private val today = LocalDate.of(2026, 9, 28)
        private val bases = mutableListOf<LocalDate>()
        private var kiwoomFails = false
        private val pages = mapOf(
            LocalDate.of(2026, 9, 27) to listOf(bar(LocalDate.of(2026, 9, 25), 9), bar(LocalDate.of(2026, 9, 24), 19)),
            LocalDate.of(2026, 9, 24) to listOf(bar(LocalDate.of(2026, 9, 24), 9), bar(LocalDate.of(2026, 9, 23), 19)),
            LocalDate.of(2026, 9, 23) to listOf(bar(LocalDate.of(2026, 9, 23), 9), bar(LocalDate.of(2026, 9, 22), 19)),
        )

        init {
            now = today.atTime(10, 0)
            every { kiwoom.fetchHistoricalMinuteCandles(any(), any()) } answers {
                bases += secondArg<LocalDate>()
                if (kiwoomFails) emptyList() else pages[secondArg<LocalDate>()].orEmpty()
            }
        }

        @Test
        fun `오늘 봉에 지난 두 거래일을 이어 붙인다`() {
            service.minuteCandles("005930", today).map { it.dateTime.toLocalDate() }.distinct() shouldBe
                listOf(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), today)
        }

        @Test
        fun `지난 날 조회의 기준일은 늘 오늘보다 앞이다`() {
            service.minuteCandles("005930", today)

            bases.shouldNotBeEmpty()
            bases.all { it < today } shouldBe true
        }

        @Test
        fun `지난 날 조회가 실패해도 오늘을 기준일로 부르지 않는다`() {
            kiwoomFails = true

            val bars = service.minuteCandles("005930", today)

            bases.all { it < today } shouldBe true
            bars.map { it.dateTime.toLocalDate() }.toSet() shouldBe setOf(today)
        }
    }

    @Nested
    inner class 저항_지지 {
        @Test
        fun `감시 풀을 저항선 근접 순으로 세우고 내린 종목도 담는다`() {
            now = LocalDateTime.of(2026, 9, 28, 10, 0)
            openDays = emptyList()
            every { kiwoom.fetchHistoricalMinuteCandles(any(), any()) } returns emptyList()
            val far = stock("000001", price = 100, rate = -5.0)
            val near = stock("000002", price = 108, rate = 2.0)
            pool(far, near)
            tossBars = { listOf(MinuteCandle(now.toLocalDate().atTime(9, 0), 100, 110, 95, 100, 1, 100)) }

            service.breakoutRadar().map { it.stock.stockCode } shouldBe listOf("000002", "000001")
        }
    }

    @Nested
    inner class 마감_확정 {
        @Test
        fun `확정된 오늘 봉을 보관소에 넘겨 마감 뒤엔 토스를 부르지 않는다`() {
            val today = LocalDate.of(2026, 9, 28)
            now = today.atTime(20, 1)
            pool(stock("005930_AL"))
            tossBars = { listOf(bar(today, 19, 59)) }
            every { kiwoom.fetchHistoricalMinuteCandles(any(), any()) } returns emptyList()

            service.settleTodayMinutes() shouldBe 0
            tossCalls.clear()

            service.minuteCandles("005930", today).filter { it.dateTime.toLocalDate() == today }.map { it.dateTime } shouldBe listOf(today.atTime(19, 59))
            tossCalls.shouldBeEmpty()
        }

        @Test
        fun `2주 지난 날은 지운다`() {
            now = LocalDateTime.of(2026, 9, 28, 20, 1)
            pool()

            service.settleTodayMinutes()

            verify { repository.deleteBefore(LocalDate.of(2026, 9, 14)) }
        }
    }
}
