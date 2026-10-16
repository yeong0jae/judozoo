package judozoo.market.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.IntegrationTest
import judozoo.library.KST
import judozoo.market.domain.FuturesInvestorSnapshotRepository
import judozoo.market.domain.MarketInvestorSnapshotRepository
import judozoo.market.domain.Region
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@IntegrationTest
class MarketInvestorsTest @Autowired constructor(
    private val snapshots: MarketInvestorSnapshotRepository,
    private val futuresSnapshots: FuturesInvestorSnapshotRepository,
    private val jdbc: JdbcTemplate,
) {
    private val calendar = mockk<MarketCalendar> {
        every { isOpen(any()) } returns null
        every { isHoliday(any()) } returns false
    }
    private val quotes = mockk<MarketQuotes>()
    private var now = LocalDateTime.of(2026, 9, 11, 21, 0)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val investors = MarketInvestors(snapshots, futuresSnapshots, calendar, quotes, clock)
    private val day = LocalDate.of(2026, 9, 11)

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM market_investor_snapshot")
        jdbc.update("DELETE FROM futures_investor_snapshot")
    }

    private fun snapshot(at: LocalDateTime, individual: Long, foreign: Long = 0, institution: Long = 0, market: Market = Market.KOSPI, index: Double = 2500.0, otherCorp: Long = 0, org: Long = 0) {
        jdbc.update(
            "INSERT INTO market_investor_snapshot (market, trade_date, captured_at, foreign_eok, institution_eok, individual_eok, other_corp_eok, " +
                "financial_investment_eok, trust_eok, pension_fund_eok, private_equity_eok, insurance_eok, bank_eok, other_finance_eok, " +
                "index_value, change_rate, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1.24, ?, ?)",
            market.name, at.toLocalDate(), at, foreign, institution, individual, otherCorp, org, org + 1, org + 2, org + 3, org + 4, org + 5, org + 6, index, at, at,
        )
    }

    private fun futuresSnapshot(at: LocalDateTime, individual: Long = 0, foreign: Long = 0, market: Market = Market.KOSPI) {
        jdbc.update(
            "INSERT INTO futures_investor_snapshot (market, trade_date, captured_at, foreign_qty, institution_qty, individual_qty, securities_qty, " +
                "insurance_qty, merchant_bank_qty, trust_qty, private_equity_qty, fund_qty, bank_qty, other_org_qty, other_corp_qty, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, -80, ?, 0, 0, 0, 0, 0, 0, 0, 0, -40, ?, ?)",
            market.name, at.toLocalDate(), at, foreign, individual, at, at,
        )
    }

    private fun at(h: Int, m: Int) = day.atTime(h, m)

    @Nested
    inner class 직전_스냅샷_대비_변화량 {
        @Test
        fun `마지막 스냅샷이 속한 구간에만 실린다`() {
            snapshot(at(9, 0), 100, -50, -50)
            snapshot(at(12, 0), 300, -150, -150)
            snapshot(at(13, 30), 380, -190, -190)
            snapshot(at(13, 31), 420, -210, -210)

            val byName = investors.investorSessions(Market.KOSPI, day).second.associate { it.name to it.delta }

            byName.getValue("오후")!!.individual shouldBe 40
            byName.getValue("오후")!!.foreign shouldBe -20
            byName.getValue("오전").shouldBeNull()
            byName.getValue("프리마켓").shouldBeNull()
        }

        @Test
        fun `지난 날짜에는 싣지 않는다`() {
            now = LocalDateTime.of(2026, 9, 14, 10, 0)
            snapshot(at(12, 0), 300, -150, -150)
            snapshot(at(13, 31), 420, -210, -210)

            investors.investorSessions(Market.KOSPI, day).second.all { it.delta == null } shouldBe true
        }

        @Test
        fun `스냅샷이 하나뿐이면 뺄 대상이 없다`() {
            snapshot(at(13, 31), 420, -210, -210)

            investors.investorSessions(Market.KOSPI, day).second.all { it.delta == null } shouldBe true
        }
    }

    @Nested
    inner class 투자자_세션_수급 {
        @Test
        fun `경계 스냅샷의 차이로 세션 순매수를 만든다`() {
            snapshot(at(9, 0), 100, -50, -50)
            snapshot(at(12, 0), 300, -150, -150)
            snapshot(at(15, 0), 500, -200, -300)

            val byName = investors.investorSessions(Market.KOSPI, day).second.associate { it.name to it.nets }

            byName.getValue("프리마켓")!!.individual shouldBe 100
            byName.getValue("오전")!!.individual shouldBe 200
            byName.getValue("오후")!!.individual shouldBe 200
            byName.getValue("오전")!!.foreign shouldBe -100
        }

        @Test
        fun `구간 합이 그날 최종 누적과 같다`() {
            listOf(at(9, 0) to 100L, at(12, 0) to 300L, at(15, 0) to 500L, at(15, 40) to 600L, at(20, 0) to 700L).forEach { (t, v) -> snapshot(t, v) }

            investors.investorSessions(Market.KOSPI, day).second.sumOf { it.nets?.individual ?: 0 } shouldBe 700
        }

        @Test
        fun `프리 스냅샷이 없으면 오전이 프리를 포함해 합을 보존한다`() {
            snapshot(at(12, 0), 300)
            snapshot(at(15, 0), 500)

            val sessions = investors.investorSessions(Market.KOSPI, day).second
            val byName = sessions.associate { it.name to it.nets }

            byName.getValue("프리마켓").shouldBeNull()
            byName.getValue("오전")!!.individual shouldBe 300
            sessions.sumOf { it.nets?.individual ?: 0 } shouldBe 500
        }

        @Test
        fun `스냅샷이 없는 세션은 null이다`() {
            val sessions = investors.investorSessions(Market.KOSPI, day).second

            sessions.map { it.nets } shouldBe List(5) { null }
            sessions.map { it.name } shouldBe listOf("프리마켓", "오전", "오후", "마감 구간", "애프터마켓")
        }

        @Test
        fun `경계 시각 이하의 가장 가까운 스냅샷을 쓴다`() {
            snapshot(at(9, 0), 100)
            snapshot(at(11, 58), 290)
            snapshot(at(12, 2), 310)

            investors.investorSessions(Market.KOSPI, day).second.first { it.name == "오전" }.nets!!.individual shouldBe 190
        }

        @Test
        fun `다른 날짜 스냅샷은 섞이지 않는다`() {
            snapshot(LocalDateTime.of(2026, 9, 10, 15, 0), 9999)
            snapshot(at(12, 0), 300)

            investors.investorSessions(Market.KOSPI, day).second.first { it.name == "오전" }.nets!!.individual shouldBe 300
        }
    }

    @Nested
    inner class 선물_세션_수급 {
        @Test
        fun `오전은 누적 그대로 이후는 차이로 낸다`() {
            futuresSnapshot(at(12, 0), foreign = 1000)
            futuresSnapshot(at(15, 0), foreign = 1500)

            val sessions = investors.futuresInvestorSessions(Market.KOSPI, day).second

            sessions.first { it.name == "오전" }.nets!!.foreign shouldBe 1000
            sessions.first { it.name == "오후" }.nets!!.foreign shouldBe 500
            sessions.map { it.name } shouldBe listOf("오전", "오후", "마감 구간")
        }
    }

    @Nested
    inner class 데이터_있는_날로_물러나기 {
        @Test
        fun `요청일에 데이터가 없으면 직전 거래일로 물러난다`() {
            snapshot(at(9, 0), 10)
            snapshot(at(12, 0), 40)

            val (used, sessions) = investors.investorSessions(Market.KOSPI, LocalDate.of(2026, 9, 14))

            used shouldBe day
            sessions.any { it.nets != null } shouldBe true
        }

        @Test
        fun `요청일에 데이터가 있으면 그날을 그대로 쓴다`() {
            snapshot(at(9, 0), 10)

            investors.investorSessions(Market.KOSPI, day).first shouldBe day
        }

        @Test
        fun `이전 데이터가 아예 없으면 요청일을 그대로 돌려준다`() {
            val longAgo = LocalDate.of(2020, 1, 2)

            val (used, sessions) = investors.investorSessions(Market.KOSPI, longAgo)

            used shouldBe longAgo
            sessions.all { it.nets == null } shouldBe true
        }

        @Test
        fun `미래 데이터로는 물러나지 않는다`() {
            snapshot(at(9, 0), 10)

            investors.investorSessions(Market.KOSPI, LocalDate.of(2026, 9, 10)).first shouldBe LocalDate.of(2026, 9, 10)
        }
    }

    @Nested
    inner class 최근_거래일_수급 {
        @BeforeEach
        fun 모두_개장일() {
            now = LocalDateTime.of(2026, 10, 2, 21, 0)
            every { calendar.isOpen(any()) } returns true
        }

        @Test
        fun `거래일마다 마지막 스냅샷을 최신순으로 준다`() {
            snapshot(LocalDateTime.of(2026, 9, 30, 10, 0), 0, foreign = 1, org = 1)
            snapshot(LocalDateTime.of(2026, 9, 30, 20, 0), 0, foreign = 30, org = 1)
            snapshot(LocalDateTime.of(2026, 10, 1, 20, 0), 0, foreign = 40, org = 1)
            snapshot(LocalDateTime.of(2026, 10, 2, 20, 0), 0, foreign = 50, org = 1)
            snapshot(LocalDateTime.of(2026, 10, 2, 20, 0), 0, foreign = 999, market = Market.KOSDAQ)

            val days = investors.investorDailyHistory(Market.KOSPI, 2)

            days.map { it.date to it.foreignEok } shouldBe listOf(LocalDate.of(2026, 10, 2) to 50L, LocalDate.of(2026, 10, 1) to 40L)
            days[0].breakdown.pensionFundEok shouldBe 3
        }

        @Test
        fun `휴장일에 찍힌 스냅샷은 건너뛴다`() {
            every { calendar.isOpen(LocalDate.of(2026, 10, 1)) } returns false
            snapshot(LocalDateTime.of(2026, 9, 30, 20, 0), 0, foreign = 30)
            snapshot(LocalDateTime.of(2026, 10, 1, 20, 0), 0, foreign = 40)
            snapshot(LocalDateTime.of(2026, 10, 2, 20, 0), 0, foreign = 50)

            investors.investorDailyHistory(Market.KOSPI, 2).map { it.date } shouldBe listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 30))
        }

        @Test
        fun `오늘 값이 전일과 같으면 키움이 되돌린 전일 값이라 오늘을 뺀다`() {
            snapshot(LocalDateTime.of(2026, 9, 30, 20, 0), 0, foreign = 30)
            snapshot(LocalDateTime.of(2026, 10, 1, 20, 0), 0, foreign = 40)
            snapshot(LocalDateTime.of(2026, 10, 2, 8, 1), 0, foreign = 40)

            investors.investorDailyHistory(Market.KOSPI, 2).map { it.date } shouldBe listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30))
        }

        @Test
        fun `선물도 거래일마다 마지막 스냅샷을 준다`() {
            futuresSnapshot(LocalDateTime.of(2026, 10, 1, 12, 0), foreign = 100)
            futuresSnapshot(LocalDateTime.of(2026, 10, 1, 15, 45), foreign = 300)
            futuresSnapshot(LocalDateTime.of(2026, 10, 2, 15, 45), foreign = 500)

            investors.futuresInvestorDailyHistory(Market.KOSPI, 5).map { it.date to it.nets.foreign } shouldBe
                listOf(LocalDate.of(2026, 10, 2) to 500L, LocalDate.of(2026, 10, 1) to 300L)
        }
    }

    @Nested
    inner class 오늘의_수급 {
        private val at = LocalDateTime.of(2026, 9, 30, 13, 31)
        private val futuresQuote = MarketQuotes.FuturesQuote(943.2, 2.71, 0.0, 0.0, 0.0, 0, 0, 0, "2026-10-01")

        @BeforeEach
        fun 장중() {
            now = at
            every { quotes.index(Market.KOSPI) } returns MarketQuotes.IndexResult(2700.0, 0.5)
            every { quotes.index(Market.KOSDAQ) } returns MarketQuotes.IndexResult(910.0, -0.2)
            every { quotes.futuresQuote(any()) } returns null
        }

        @Test
        fun `두 시장의 가장 최근 스냅샷을 읽는다`() {
            snapshot(at.withMinute(29), 100, 20, -10, index = 2600.0, otherCorp = 5)
            snapshot(at.withMinute(30), 200, 20, -10, index = 2653.81, otherCorp = 5)
            snapshot(at.withMinute(30), -300, market = Market.KOSDAQ, index = 900.12)

            val result = investors.todayNets()

            result.map { it.market to it.futures } shouldBe listOf(Market.KOSPI to false, Market.KOSDAQ to false)
            (result[0].indexValue to result[0].changeRate) shouldBe (2653.81 to 1.24)
            result[0].nets shouldBe MarketInvestors.TodayNetInvestors(200, 20, -10, 5)
            result[1].nets!!.individual shouldBe -300
            result.map { it.tradeDate }.toSet() shouldBe setOf(at.toLocalDate())
        }

        @Test
        fun `장중에는 지난날 수급을 오늘 카드에 섞지 않는다`() {
            snapshot(at.withDayOfMonth(29), 999, index = 2500.0)

            investors.todayNets().map { it.indexValue to it.nets } shouldBe listOf(2700.0 to null, 910.0 to null)
        }

        @Test
        fun `선물은 시세와 오늘 수급 스냅샷을 함께 보여준다`() {
            every { quotes.futuresQuote(any()) } returns futuresQuote
            futuresSnapshot(at.withMinute(30), individual = -4210, foreign = 120)
            futuresSnapshot(at, individual = -4220, foreign = 120)
            futuresSnapshot(at, individual = 630, foreign = 120, market = Market.KOSDAQ)

            val result = investors.todayNets()

            result.map { it.market to it.futures } shouldBe listOf(Market.KOSPI to false, Market.KOSDAQ to false, Market.KOSPI to true, Market.KOSDAQ to true)
            (result[2].indexValue to result[2].changeRate) shouldBe (943.2 to 2.71)
            result[2].nets shouldBe MarketInvestors.TodayNetInvestors(-4220, 120, -80, -40)
            result[3].nets!!.individual shouldBe 630
        }

        @Test
        fun `장중에 오늘 선물 스냅샷이 없으면 지난날 수급을 표시하지 않는다`() {
            every { quotes.futuresQuote(any()) } returns futuresQuote
            futuresSnapshot(at.withDayOfMonth(29), individual = 999)

            val futures = investors.todayNets().filter { it.futures }

            futures.size shouldBe 2
            futures.all { it.nets == null && it.indexValue == 943.2 } shouldBe true
        }

        @Test
        fun `자정 직후에는 직전 거래일 마지막 수급을 그 날짜와 함께 보여준다`() {
            now = LocalDateTime.of(2026, 10, 1, 0, 11)
            every { quotes.futuresQuote(any()) } returns futuresQuote
            snapshot(LocalDateTime.of(2026, 9, 30, 19, 58), 100, index = 2600.0)
            snapshot(LocalDateTime.of(2026, 9, 30, 20, 0), 200, index = 2653.81)
            snapshot(LocalDateTime.of(2026, 9, 30, 20, 0), -300, market = Market.KOSDAQ, index = 900.12)
            futuresSnapshot(LocalDateTime.of(2026, 9, 30, 15, 45), individual = -4220)
            futuresSnapshot(LocalDateTime.of(2026, 9, 30, 15, 45), individual = 630, market = Market.KOSDAQ)

            val result = investors.todayNets()

            val previous = LocalDate.of(2026, 9, 30)
            result.map { listOf(it.market, it.futures, it.nets!!.individual, it.tradeDate) } shouldBe listOf(
                listOf(Market.KOSPI, false, 200L, previous), listOf(Market.KOSDAQ, false, -300L, previous),
                listOf(Market.KOSPI, true, -4220L, previous), listOf(Market.KOSDAQ, true, 630L, previous),
            )
            result[0].indexValue shouldBe 2653.81
        }

        @Test
        fun `휴장일에는 장중 시각에도 직전 거래일 수급을 보여준다`() {
            now = LocalDateTime.of(2026, 10, 3, 13, 0)
            every { calendar.isHoliday(Region.KR) } returns true
            snapshot(LocalDateTime.of(2026, 10, 2, 20, 0), 200, index = 2653.81)
            snapshot(LocalDateTime.of(2026, 10, 2, 20, 0), -300, market = Market.KOSDAQ, index = 900.12)

            investors.todayNets().map { it.nets!!.individual to it.tradeDate } shouldBe
                listOf(200L to LocalDate.of(2026, 10, 2), -300L to LocalDate.of(2026, 10, 2))
        }

        @Test
        fun `오늘 첫 스냅샷이 들어오면 오늘 수급으로 바뀐다`() {
            now = LocalDateTime.of(2026, 10, 1, 8, 1)
            snapshot(LocalDateTime.of(2026, 9, 30, 20, 0), 200, index = 2653.81)
            snapshot(LocalDateTime.of(2026, 10, 1, 8, 0), 7, index = 2660.0)

            val kospi = investors.todayNets()[0]

            (kospi.nets!!.individual to kospi.tradeDate) shouldBe (7L to LocalDate.of(2026, 10, 1))
        }

        @Test
        fun `시세와 스냅샷이 모두 없으면 빈 목록이다`() {
            every { quotes.index(any()) } throws IllegalStateException("시세 실패")

            investors.todayNets().shouldBeEmpty()
        }
    }
}
