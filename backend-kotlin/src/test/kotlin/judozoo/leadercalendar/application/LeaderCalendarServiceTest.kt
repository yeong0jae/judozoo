package judozoo.leadercalendar.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.IntegrationTest
import judozoo.leadercalendar.domain.LeaderDayRepository
import judozoo.leadercalendar.domain.LeaderDayStockRepository
import judozoo.leadertimeline.application.LeaderTimelineService
import judozoo.leadertimeline.domain.LeaderTickRepository
import judozoo.leadertimeline.domain.LeaderTickStockRepository
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.library.KST
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.overseasleadingstock.domain.OverseasStockRank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 주도주 캘린더 — 마감 스냅샷이 남기는 것과 월 조회. */
@IntegrationTest
class LeaderCalendarServiceTest @Autowired constructor(
    private val days: LeaderDayRepository,
    private val stocks: LeaderDayStockRepository,
    private val ticks: LeaderTickRepository,
    private val tickStocks: LeaderTickStockRepository,
    private val transaction: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {
    private val at = LocalDateTime.of(2026, 9, 23, 20, 1)
    private val day = LocalDate.of(2026, 9, 23)
    private var now = LocalDateTime.of(2026, 9, 25, 12, 0)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val domestic = mockk<LeadingStockService>()
    private val overseas = mockk<OverseasLeadingStockService>()
    private val timeline = LeaderTimelineService(ticks, tickStocks, domestic, overseas)
    private val service = LeaderCalendarService(days, stocks, timeline, domestic, overseas, clock)

    private fun stock(code: String, name: String) = LeadingStockSnapshot(code, name, 70_000, 5.0, 1, 1_234_500_000_000)

    private fun foreign(symbol: String, name: String) = OverseasStockRank(1, "NAS", symbol, name, symbol, 182.37, 3.0, 2.6, 44_300_000_000.5)

    private fun leaders(vararg s: LeadingStockSnapshot) {
        every { domestic.findLeaders(5) } returns s.toList()
    }

    /** 서비스 메서드는 프록시 밖에서 부르므로 트랜잭션을 직접 건다. */
    private fun <T> tx(block: () -> T): T = transaction.execute { block() }!!

    private fun domesticMonth(year: Int = 2026, month: Int = 9) = tx { service.findMonth(year, month) }.first

    @BeforeEach
    fun 빈_캘린더() {
        listOf("leader_day_stock", "leader_day", "leader_tick_stock", "leader_tick").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Nested
    inner class 국내_마감_스냅샷 {
        @Test
        fun `홈 주도주를 순서대로 남기고 거래소 접미사를 뗀다`() {
            leaders(stock("000660", "SK하이닉스"), stock("009150_AL", "삼성전기"))

            tx { service.snapshotDomestic(day, at) }

            val month = domesticMonth()
            month.map { it.tradeDate } shouldBe listOf(day)
            month[0].stocks.map { Triple(it.rank, it.code, it.name) } shouldBe listOf(Triple(1, "000660", "SK하이닉스"), Triple(2, "009150", "삼성전기"))
        }

        @Test
        fun `주도주가 없는 날도 날은 남는다`() {
            leaders()

            tx { service.snapshotDomestic(day, at) }

            domesticMonth().map { it.tradeDate to it.stocks } shouldBe listOf(day to emptyList())
        }

        @Test
        fun `같은 날을 다시 찍으면 한 벌만 남는다`() {
            leaders(stock("000660", "SK하이닉스"), stock("005930", "삼성전자"))
            tx { service.snapshotDomestic(day, at) }
            leaders(stock("042700", "한미반도체"))

            tx { service.snapshotDomestic(day, at.plusMinutes(29)) }

            domesticMonth().single().stocks.map { it.name } shouldBe listOf("한미반도체")
        }

        @Test
        fun `휴장일은 종목 없이 휴장으로 남는다`() {
            tx { service.recordClosed(Region.KR, LocalDate.of(2026, 9, 24), at) }

            domesticMonth().map { Triple(it.tradeDate, it.stocks, it.closed) } shouldBe listOf(Triple(LocalDate.of(2026, 9, 24), emptyList(), true))
        }

        /** 0개로 남기면 브로커 오류가 "주도주 없음"으로 굳는다. */
        @Test
        fun `주도주를 못 받으면 아무것도 남기지 않는다`() {
            every { domestic.findLeaders(5) } throws IllegalStateException("키움 오류")

            shouldThrow<IllegalStateException> { tx { service.snapshotDomestic(day, at) } }

            domesticMonth().shouldBeEmpty()
        }
    }

    @Nested
    inner class 해외_마감_스냅샷 {
        @Test
        fun `거래소와 달러 값을 그대로 남긴다`() {
            every { overseas.leaders(5) } returns listOf(foreign("NVDA", "엔비디아"))

            tx { service.snapshotOverseas(LocalDate.of(2026, 9, 22), at) }

            val stock = tx { service.findMonth(2026, 9) }.second[0].stocks[0]
            Triple(stock.exchange, stock.code, stock.name) shouldBe Triple("NAS", "NVDA", "엔비디아")
            stock.price.toDouble() shouldBe 182.37
            stock.tradingValue.toDouble() shouldBe 44_300_000_000.5
            stock.changeRate shouldBe 2.6
        }
    }

    @Nested
    inner class 월_조회 {
        /** 9/1 칸에는 8/31 해외장이 붙는다. 국내 8/31은 9월 달력에 필요 없다. */
        @Test
        fun `해외는 그달 첫날 직전 평일부터 온다`() {
            leaders(stock("000660", "SK하이닉스"))
            every { overseas.leaders(5) } returns listOf(foreign("TSLA", "테슬라"))
            listOf(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)).forEach {
                tx { service.snapshotDomestic(it, at) }
                tx { service.snapshotOverseas(it, at) }
            }

            val (domesticDays, overseasDays) = tx { service.findMonth(2026, 9) }

            domesticDays.map { it.tradeDate } shouldBe listOf(LocalDate.of(2026, 9, 1))
            overseasDays.map { it.tradeDate } shouldBe listOf(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1))
        }
    }

    @Nested
    inner class 장중_오늘 {
        private fun tick(minute: LocalDateTime, vararg s: LeadingStockSnapshot) {
            leaders(*s)
            tx { timeline.snapshot(Region.KR, minute, minute) }
        }

        @Test
        fun `마감 기록 전에는 타임라인의 마지막 분으로 채우고 진행 중으로 표시한다`() {
            tick(day.atTime(10, 0), stock("005930", "삼성전자"))
            tick(day.atTime(10, 1), stock("000660", "SK하이닉스"), stock("005930", "삼성전자"))
            now = day.atTime(10, 1, 30)

            val month = domesticMonth()

            month.map { it.tradeDate to it.live } shouldBe listOf(day to true)
            month[0].stocks.map { it.rank to it.name } shouldBe listOf(1 to "SK하이닉스", 2 to "삼성전자")
        }

        @Test
        fun `마감 기록이 생기면 그것을 준다`() {
            tick(day.atTime(20, 0), stock("005930", "삼성전자"))
            leaders(stock("042700", "한미반도체"))
            tx { service.snapshotDomestic(day, at) }
            now = day.atTime(20, 5)

            val month = domesticMonth()

            month.map { it.tradeDate to it.live } shouldBe listOf(day to false)
            month[0].stocks.map { it.name } shouldBe listOf("한미반도체")
        }

        /** 장 전에는 풀이 어제 값을 들고 있다 — 타임라인이 찍지 않았으니 오늘 칸에 들어오지 않는다. */
        @Test
        fun `오늘 찍힌 분이 없으면 비워 둔다`() {
            tick(LocalDateTime.of(2026, 9, 22, 20, 0), stock("005930", "삼성전자"))
            now = day.atTime(7, 30)

            domesticMonth().filter { it.live }.shouldBeEmpty()
        }

        @Test
        fun `다른 달을 보면 오늘을 채우지 않는다`() {
            tick(day.atTime(10, 0), stock("005930", "삼성전자"))
            now = day.atTime(10, 0, 30)

            domesticMonth(month = 8).shouldBeEmpty()
        }
    }
}
