package judozoo.leadingstock.application

import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import judozoo.IntegrationTest
import judozoo.leadingstock.domain.MarketFlowStateRepository
import judozoo.leadingstock.domain.MarketSignalEventRepository
import judozoo.library.KST
import judozoo.market.application.MarketInvestors
import judozoo.market.domain.MarketInvestorSnapshotRepository
import judozoo.platform.kiwoom.KiwoomSectorInvestor
import judozoo.platform.kiwoom.SectorInvestorNetBuy
import judozoo.stock.domain.Market
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@IntegrationTest
class MarketFlowDetectorTest @Autowired constructor(
    private val investors: MarketInvestors,
    private val events: MarketSignalEventRepository,
    private val flowStates: MarketFlowStateRepository,
    private val snapshots: MarketInvestorSnapshotRepository,
    private val jdbc: JdbcTemplate,
) {
    @MockkBean
    private lateinit var sector: KiwoomSectorInvestor

    private var now = LocalDateTime.of(2026, 9, 11, 10, 0)
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }

    private fun fresh() = MarketFlowDetector(sector, investors, events, flowStates, clock)

    private fun kospi(foreign: Long, indexValue: Double = 2500.0, changeRate: Double = 1.5) {
        every { sector.fetchSectorNetBuy(Market.KOSPI) } returns SectorInvestorNetBuy(foreign, 50, -150, 0, 10, 10, 10, 10, 5, 5, 0, indexValue, changeRate)
        every { sector.fetchSectorNetBuy(Market.KOSDAQ) } returns null
    }

    private fun kinds() = jdbc.queryForList("SELECT CONCAT(kind, ':', investor, ':', side) FROM market_signal_event ORDER BY id", String::class.java)

    @BeforeEach
    fun 빈_테이블() {
        listOf("market_investor_snapshot", "market_signal_event", "market_flow_state").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Nested
    inner class 수급_스냅샷 {
        @Test
        fun `지수 레벨과 등락률을 함께 남긴다`() {
            kospi(100, indexValue = 2653.81, changeRate = 1.24)

            fresh().detect()

            val row = snapshots.findAll().single()
            (row.foreignEok to row.capturedAt) shouldBe (100L to now)
            (row.indexValue to row.changeRate) shouldBe (2653.81 to 1.24)
        }

        @Test
        fun `정규장 밖에는 스냅샷만 남기고 시그널은 내지 않는다`() {
            now = LocalDateTime.of(2026, 9, 11, 8, 30)
            kospi(25_000)

            fresh().detect()

            snapshots.count() shouldBe 1
            kinds().shouldBeEmpty()
        }
    }

    @Nested
    inner class 시그널 {
        @Test
        fun `단계선을 넘으면 단계 시그널을 낸다`() {
            kospi(10_000)

            fresh().detect()

            kinds() shouldBe listOf("NET_BUY_LEVEL:FOREIGN:BUY")
            jdbc.queryForObject("SELECT step_level FROM market_signal_event", Int::class.java) shouldBe 1
        }

        @Test
        fun `재시작해도 그날 같은 단계는 한 번만 낸다`() {
            kospi(10_000)
            fresh().detect()
            now = now.plusMinutes(1)

            fresh().detect()

            kinds() shouldBe listOf("NET_BUY_LEVEL:FOREIGN:BUY")
        }

        @Test
        fun `재시작해도 흐름 전환 정점을 복원해 전환을 잡는다`() {
            kospi(-6_800)
            fresh().detect()
            now = now.plusMinutes(1)
            kospi(-5_500)

            fresh().detect()

            kinds() shouldBe listOf("NET_FLOW_TURN:FOREIGN:BUY")
            jdbc.queryForObject("SELECT extreme_amount_eok FROM market_signal_event", Long::class.java) shouldBe -6_800
        }

        @Test
        fun `날이 바뀌면 어제 정점으로 전환을 내지 않는다`() {
            kospi(-6_800)
            fresh().detect()
            now = now.plusDays(1)
            kospi(-5_500)

            fresh().detect()

            kinds().shouldBeEmpty()
        }
    }
}
