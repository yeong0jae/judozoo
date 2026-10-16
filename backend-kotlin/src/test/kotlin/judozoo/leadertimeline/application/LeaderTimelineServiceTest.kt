package judozoo.leadertimeline.application

import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import judozoo.IntegrationTest
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.market.domain.Region
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.overseasleadingstock.domain.OverseasStockRank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

/** 주도주 타임라인 — 1분 스냅샷이 남기는 것과 하루 조회. */
@IntegrationTest
class LeaderTimelineServiceTest @Autowired constructor(private val service: LeaderTimelineService, private val jdbc: JdbcTemplate) {
    @MockkBean
    private lateinit var domestic: LeadingStockService

    @MockkBean
    private lateinit var overseas: OverseasLeadingStockService

    private val day = LocalDate.of(2026, 9, 23)
    private val taken = LocalDateTime.of(2026, 9, 23, 10, 15, 1)

    private fun minute(h: Int, m: Int) = day.atTime(h, m)

    private fun stock(code: String, name: String) = LeadingStockSnapshot(code, name, 70_000, 5.0, 1, 1_234_500_000_000)

    private fun leaders(vararg stocks: LeadingStockSnapshot) {
        every { domestic.findLeaders(5) } returns stocks.toList()
    }

    @BeforeEach
    fun 빈_타임라인() {
        jdbc.update("DELETE FROM leader_tick_stock")
        jdbc.update("DELETE FROM leader_tick")
    }

    @Nested
    inner class `1분_스냅샷` {
        @Test
        fun `그 분의 주도주를 순서대로 남긴다`() {
            leaders(stock("000660", "SK하이닉스"), stock("009150_AL", "삼성전기"))

            service.snapshot(Region.KR, minute(10, 15), taken)

            val ticks = service.findDay(Region.KR, day)
            ticks.map { it.at to it.takenAt } shouldBe listOf(minute(10, 15) to taken)
            ticks[0].stocks.map { Triple(it.rank, it.code, it.name) } shouldBe listOf(Triple(1, "000660", "SK하이닉스"), Triple(2, "009150", "삼성전기"))
            ticks[0].stocks[0].changeRate shouldBe 5.0
        }

        @Test
        fun `주도주가 없는 분도 분은 남는다`() {
            leaders()

            service.snapshot(Region.KR, minute(14, 25), taken)

            service.findDay(Region.KR, day).map { it.at to it.stocks } shouldBe listOf(minute(14, 25) to emptyList())
        }

        @Test
        fun `같은 분을 다시 찍으면 한 벌만 남는다`() {
            leaders(stock("000660", "SK하이닉스"), stock("005930", "삼성전자"))
            service.snapshot(Region.KR, minute(10, 15), taken)
            leaders(stock("042700", "한미반도체"))

            service.snapshot(Region.KR, minute(10, 15), taken)

            service.findDay(Region.KR, day).single().stocks.map { it.name } shouldBe listOf("한미반도체")
        }

        /** 0개로 남기면 브로커 오류가 "주도주 없음"으로 굳는다. */
        @Test
        fun `주도주를 못 받으면 그 분은 남지 않는다`() {
            every { domestic.findLeaders(5) } throws IllegalStateException("키움 오류")

            shouldThrow<IllegalStateException> { service.snapshot(Region.KR, minute(10, 15), taken) }

            service.findDay(Region.KR, day).shouldBeEmpty()
        }

        @Test
        fun `해외는 거래소와 달러 값을 남긴다`() {
            every { overseas.leaders(5) } returns listOf(OverseasStockRank(1, "NAS", "NVDA", "엔비디아", "NVIDIA", 182.37, 3.0, 2.6, 44_300_000_000.5))

            service.snapshot(Region.US, minute(11, 30), taken)

            val stock = service.findDay(Region.US, day)[0].stocks[0]
            (stock.exchange to stock.code) shouldBe ("NAS" to "NVDA")
            stock.tradingValue.toDouble() shouldBe 44_300_000_000.5
            stock.price.toDouble() shouldBe 182.37
        }
    }

    @Nested
    inner class 하루_조회 {
        @Test
        fun `시각 순으로 주고 since면 그 뒤만 준다`() {
            leaders(stock("000660", "SK하이닉스"))
            listOf(17, 15, 16).forEach { service.snapshot(Region.KR, minute(10, it), taken) }

            service.findDay(Region.KR, day).map { it.at } shouldBe listOf(minute(10, 15), minute(10, 16), minute(10, 17))
            service.findDay(Region.KR, day, since = minute(10, 15)).map { it.at } shouldBe listOf(minute(10, 16), minute(10, 17))
        }

        @Test
        fun `시장이 다른 날은 섞이지 않는다`() {
            leaders(stock("000660", "SK하이닉스"))
            service.snapshot(Region.KR, minute(10, 15), taken)

            service.findDay(Region.US, day).shouldBeEmpty()
        }
    }

    @Nested
    inner class 마지막_분 {
        @Test
        fun `그날 가장 늦은 분을 순위 순으로 준다`() {
            leaders(stock("005930", "삼성전자"))
            service.snapshot(Region.KR, minute(10, 15), taken)
            leaders(stock("000660", "SK하이닉스"), stock("005930", "삼성전자"))
            service.snapshot(Region.KR, minute(10, 16), taken)

            val tick = service.latest(Region.KR, day)!!

            tick.at shouldBe minute(10, 16)
            tick.stocks.map { it.rank to it.name } shouldBe listOf(1 to "SK하이닉스", 2 to "삼성전자")
        }

        @Test
        fun `찍힌 분이 없으면 없다`() {
            service.latest(Region.KR, day).shouldBeNull()
        }
    }
}
