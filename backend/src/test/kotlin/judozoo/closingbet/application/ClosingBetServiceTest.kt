package judozoo.closingbet.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import judozoo.IntegrationTest
import judozoo.closingbet.domain.BetPlayer
import judozoo.closingbet.domain.BetPlayerRepository
import judozoo.closingbet.domain.BetRejected
import judozoo.closingbet.domain.BetRoundRepository
import judozoo.closingbet.domain.BetRoundStockRepository
import judozoo.closingbet.domain.ClosingBetEventRepository
import judozoo.closingbet.domain.ClosingBetRepository
import judozoo.closingbet.domain.NicknameRejected
import judozoo.leadingstock.application.LeadingStockService
import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.application.MarketInvestors
import judozoo.market.application.MarketQuotes
import judozoo.platform.kiwoom.KiwoomMarket
import judozoo.stock.application.StockCatalog
import judozoo.stock.application.StockInvestorService
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@IntegrationTest
class ClosingBetServiceTest @Autowired constructor(
    private val bets: ClosingBetRepository,
    private val events: ClosingBetEventRepository,
    private val players: BetPlayerRepository,
    private val rounds: BetRoundRepository,
    private val roundStocks: BetRoundStockRepository,
    private val transactions: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) {
    private val thu = LocalDate.of(2026, 10, 1)
    private val fri = LocalDate.of(2026, 10, 2)
    private var now = thu.atTime(16, 0)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }

    private fun stock(code: String, name: String, price: Long) = LeadingStockSnapshot("${code}_AL", name, price, 5.0, 1, 1)

    private var leaders = listOf(stock("000660", "SK하이닉스", 412_500))
    private var candidates = listOf(stock("247540", "에코프로비엠", 198_400), stock("277810", "레인보우로보틱스", 341_500))
    private val leading = mockk<LeadingStockService> {
        every { findLeaders(5) } answers { leaders }
        every { findCandidates(0.0) } answers { candidates }
    }
    private val closes = mapOf("000660" to 400_000L, "247540" to 200_000L, "277810" to 300_000L)

    private fun daily(code: String, base: LocalDate): List<DailyCandle> {
        val close = closes.getValue(code)
        val pastHigh = if (code == "000660") close - 1 else close * 2
        return listOf(DailyCandle(base, close, close, close - 10_000, close, 1, 5.0)) +
            (0 until 60).map { DailyCandle(LocalDate.of(2026, 7, 1 + it % 28), 0, pastHigh, 0, 0, 1, 0.0) }
    }

    private fun bar(day: LocalDate, h: Int, m: Int, high: Long, low: Long) = MinuteCandle(day.atTime(h, m), low, high, low, high, 10, 0)

    private var minuteBars: (String, LocalDate) -> List<MinuteCandle> = { code, day -> listOf(bar(day, 15, 30, closes.getValue(code) - 1_000, closes.getValue(code) - 1_000)) }
    private val kiwoom = mockk<KiwoomMarket> {
        every { fetchDailyCandles(any(), 61, any()) } answers { daily(firstArg(), thirdArg()) }
        every { fetchNxtListed(any()) } answers { firstArg<String>() != "277810" }
        every { fetchHistoricalMinuteCandles(any(), any()) } answers { minuteBars(firstArg(), secondArg()) }
    }
    private val catalog = mockk<StockCatalog> { every { marketOf(any()) } answers { if (firstArg<String>() == "000660") Market.KOSPI else Market.KOSDAQ } }
    private val investors = mockk<StockInvestorService> {
        every { investorDailyHistory(any(), 5) } answers {
            val sign = if (firstArg<String>() == "000660") 1 else -1
            listOf(StockInvestorService.InvestorDay(thu, 0, sign * 100L, sign * 50L, 0, StockInvestorService.OrgBreakdown(0, 0, 0, 0, 0, 0, 0)))
        }
    }
    private val calendar = mockk<MarketCalendar> { every { openDays() } returns setOf(LocalDate.of(2026, 9, 30), thu, fri, LocalDate.of(2026, 10, 5)) }
    private val service = spyk(
        ClosingBetService(
            bets, events, players, BetPlayers(players, transactions), rounds, roundStocks, leading, kiwoom, catalog, investors,
            mockk<MarketInvestors>(), mockk<MarketQuotes>(), calendar, transactions, clock,
        ),
    ) { every { marketFlows(any()) } returns FLOWS }

    @BeforeEach
    fun 빈_테이블() {
        listOf("bet_player", "closing_bet", "closing_bet_event", "bet_round", "bet_round_stock").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Nested
    inner class 베팅 {
        @Test
        fun `걸고 바꾸고 취소하면 LIVE에 차례로 남는다`() {
            service.place(1, "000660", 2_000, thu.atTime(15, 10))
            service.place(1, "247540", 2_000, thu.atTime(15, 20))
            service.cancel(1, thu.atTime(15, 30))

            service.myBet(1, thu).shouldBeNull()
            service.feed(thu).map { it.stockName to it.amountMan } shouldBe listOf("에코프로비엠" to -2_000, "에코프로비엠" to 2_000, "SK하이닉스" to -2_000, "SK하이닉스" to 2_000)
        }

        @Test
        fun `처음 걸면 랜덤 닉네임이 생긴다`() {
            service.place(7, "000660", 100, thu.atTime(16, 0))

            players.findById(7).get().nickname.shouldNotBeEmpty()
        }

        @Test
        fun `하루에 한 종목만 남는다`() {
            service.place(1, "000660", 2_000, thu.atTime(15, 10))
            service.place(1, "247540", 3_000, thu.atTime(15, 20))

            bets.findAll().map { it.stockCode to it.amountMan } shouldBe listOf("247540" to 3_000)
        }

        @Test
        fun `20시가 되면 받지 않는다`() {
            shouldThrow<BetRejected> { service.place(1, "000660", 2_000, thu.atTime(20, 0)) }
        }

        @Test
        fun `지금 목록에 없는 종목은 못 고른다`() {
            shouldThrow<BetRejected> { service.place(1, "005930", 2_000, thu.atTime(16, 0)) }.message!! shouldContain "목록"
        }

        @Test
        fun `판돈과 참여 수`() {
            service.place(1, "000660", 2_000, thu.atTime(15, 10))
            service.place(2, "000660", 500, thu.atTime(15, 11))

            val stakes = service.stakes(thu)
            Triple(stakes.totalMan, stakes.players, stakes.byStock()) shouldBe Triple(2_500L, 2, mapOf("000660" to 2_500L))
        }
    }

    @Nested
    inner class `20시_체결` {
        @Test
        fun `종가로 사고 판을 그대로 찍어 둔다`() {
            service.place(1, "000660", 2_000, thu.atTime(15, 10))
            now = thu.atTime(20, 1)

            service.fillRound(thu, thu.atTime(20, 1)) shouldBe true

            val bet = service.myBet(1, thu)!!
            Triple(bet.status, bet.buyPrice, bet.shares) shouldBe Triple("filled", 400_000L, 50)
            val stocks = service.roundStocks(thu).associateBy { it.stockCode }
            val hynix = stocks.getValue("000660")
            listOf(hynix.grade, hynix.nxt, hynix.crowd, hynix.potMan) shouldBe listOf("S", true, 1, 2_000L)
            hynix.regularClose shouldBe 399_000
            JsonMapper.builder().build().readTree(hynix.checksJson)["after_hold"].asBoolean() shouldBe true
            hynix.recentHighGap!! shouldBe (0.00025 plusOrMinus 1e-4)
            stocks.getValue("277810").nxt shouldBe false
            val round = service.betRound(thu)!!
            (round.players to round.potMan) shouldBe (1 to 2_000L)
            JsonMapper.builder().build().readTree(round.marketJson) shouldBe JsonMapper.builder().build().valueToTree(FLOWS)
        }

        @Test
        fun `목록에서 빠진 종목에 건 베팅도 체결한다`() {
            service.place(1, "247540", 1_000, thu.atTime(15, 10))
            candidates = emptyList()

            service.fillRound(thu, thu.atTime(20, 1))

            service.myBet(1, thu)!!.status shouldBe "filled"
            service.roundStocks(thu).map { it.stockCode }.contains("247540") shouldBe true
        }

        @Test
        fun `한 주도 못 사게 오르면 판에서 뺀다`() {
            service.place(1, "277810", 100, thu.atTime(15, 10))
            jdbc.update("UPDATE closing_bet SET amount_man = 20")

            service.fillRound(thu, thu.atTime(20, 1))

            service.myBet(1, thu)!!.status shouldBe "void"
        }

        @Test
        fun `두 번 불러도 한 번만 찍는다`() {
            service.fillRound(thu, thu.atTime(20, 1))
            service.fillRound(thu, thu.atTime(20, 2))

            service.roundStocks(thu).size shouldBe 3
        }
    }

    @Nested
    inner class 아침_정산 {
        @BeforeEach
        fun 체결된_판() {
            service.place(1, "000660", 2_000, thu.atTime(15, 10))
            service.place(2, "277810", 2_000, thu.atTime(15, 20))
            now = thu.atTime(20, 1)
            service.fillRound(thu, thu.atTime(20, 1))
            val morning = mapOf(
                "000660" to listOf(bar(fri, 8, 0, 410_000, 406_000)),
                "247540" to listOf(bar(fri, 8, 1, 201_000, 199_000)),
                "277810" to listOf(bar(fri, 9, 2, 306_000, 300_000)),
            )
            minuteBars = { code, _ -> morning.getValue(code) }
        }

        @Test
        fun `NXT 종목은 08시05분에 먼저 판다`() {
            service.settleRound(thu, fri, fri.atTime(8, 5)) shouldBe false

            val hynix = service.myBet(1, thu)!!
            Triple(hynix.status, hynix.sellPrice, hynix.pnl) shouldBe Triple("settled", 408_000.0, 50L * 8_000)
            service.myBet(2, thu)!!.status shouldBe "filled"
        }

        @Test
        fun `09시05분에 나머지를 팔고 랭킹을 확정한다`() {
            service.settleRound(thu, fri, fri.atTime(8, 5))

            service.settleRound(thu, fri, fri.atTime(9, 7)) shouldBe true

            service.betRound(thu)!!.settledAt shouldBe fri.atTime(9, 7)
            service.results(thu).ranked(byRate = false).map { Triple(it.rank, it.result.userId, it.result.outcome.pnl) } shouldBe
                listOf(Triple(1, 1L, 400_000L), Triple(2, 2L, 198_000L))
        }

        @Test
        fun `매분 확인하면 아침엔 직전 판을 판다`() {
            service.tick(fri.atTime(9, 7))

            service.betRound(thu)!!.settled shouldBe true
        }
    }

    @Nested
    inner class 닉네임 {
        @Test
        fun `남이 쓰는 닉네임은 못 쓴다`() {
            players.save(BetPlayer(1, "새벽올빼미", thu.atTime(9, 0)))

            shouldThrow<NicknameRejected> { service.rename(2, "새벽올빼미", thu.atTime(10, 0)) }.message!! shouldContain "이미"
        }

        @Test
        fun `바꾸면 7일 잠긴다`() {
            service.rename(1, "수달왕", thu.atTime(10, 0))

            shouldThrow<NicknameRejected> { service.rename(1, "수달여왕", fri.atTime(10, 0)) }.message!! shouldContain "10/8"
            players.findById(1).get().nickname shouldBe "수달왕"
        }

        @Test
        fun `사용자 번호로 닉네임을 찾는다`() {
            players.saveAll(listOf(BetPlayer(1, "새벽올빼미", thu.atTime(9, 0)), BetPlayer(2, "수달왕", thu.atTime(9, 0))))

            service.nicknames(setOf(1, 2, 3)) shouldBe mapOf(1L to "새벽올빼미", 2L to "수달왕")
            service.nicknames(emptySet()) shouldBe emptyMap()
        }
    }

    companion object {
        val FLOWS: Map<String, Any?> = mapOf(
            "KOSPI" to mapOf("foreign" to listOf(10, 50, 1, 5, 30, 5), "institution" to listOf(-5, -10, -1, -5, 10, -2)),
            "KOSDAQ" to mapOf("foreign" to listOf(1, 2, 1, 2, -30, -5), "institution" to listOf(1, 2, 1, 2, -10, -2)),
            "night" to mapOf("price" to 413.6, "rate" to 0.35),
        )
    }
}
