package judozoo.leadingstock.application

import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.mockk.every
import judozoo.IntegrationTest
import judozoo.leadingstock.domain.IndexMinuteCandleRepository
import judozoo.leadingstock.domain.IndexTick
import judozoo.leadingstock.domain.MarketSignalEventRepository
import judozoo.library.KST
import judozoo.platform.kiwoom.KiwoomIndex
import judozoo.platform.kiwoom.KiwoomIndex.IndexIntraday
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

/**
 * 지수 반등·꺾임 폴러. 키움은 최근 몇 분의 틱만 주므로 받은 분봉을 쌓아 이평을 낸다 —
 * 쌓은 분봉을 DB에 두어 재시작해도 이평이 끊기지 않는다.
 */
@IntegrationTest
class IndexReboundDetectorTest @Autowired constructor(
    private val candles: IndexMinuteCandleRepository,
    private val events: MarketSignalEventRepository,
    private val jdbc: JdbcTemplate,
) {
    @MockkBean
    private lateinit var index: KiwoomIndex

    private val open = LocalDateTime.of(2026, 9, 11, 9, 0)
    private var now = open
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }

    private fun fresh() = IndexReboundDetector(index, candles, events, clock)

    /** [closes]를 5분봉 종가로 하나씩 받는다 — 매 폴 그 5분봉의 첫 1분 틱 하나만 온다. 끝난 5분봉을 판정하려면 다음 봉이 시작돼야 한다. */
    private fun poll(detector: IndexReboundDetector, vararg closes: Double) = closes.forEach { close ->
        every { index.fetchIndexIntraday(Market.KOSPI, any()) } returns IndexIntraday(close, 0.5, listOf(IndexTick(now, close, 1)))
        every { index.fetchIndexIntraday(Market.KOSDAQ, any()) } returns null
        detector.detect()
        now = now.plusMinutes(5)
    }

    private fun fired(kind: String) = jdbc.queryForObject("SELECT COUNT(*) FROM market_signal_event WHERE kind = ?", Int::class.java, kind)

    @BeforeEach
    fun 빈_테이블() {
        listOf("index_minute_candle", "market_signal_event").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Nested
    inner class 반등과_꺾임 {
        @Test
        fun `이평 위로 올라서면 반등을 한 번 내고 다시 올라서도 무장 전엔 내지 않는다`() {
            val detector = fresh()
            poll(detector, *DoubleArray(20) { 100.0 }, 99.0, 102.0, 100.0)

            fired("MA_REBOUND") shouldBe 1
            jdbc.queryForObject("SELECT index_value FROM market_signal_event WHERE kind = 'MA_REBOUND'", Double::class.java) shouldBe 100.0

            poll(detector, 99.9, 103.0, 100.0)

            fired("MA_REBOUND") shouldBe 1
        }

        @Test
        fun `이평 아래로 내려서면 꺾임을 낸다`() {
            poll(fresh(), *DoubleArray(20) { 100.0 }, 101.0, 98.0, 100.0)

            fired("MA_BREAKDOWN") shouldBe 1
        }
    }

    @Nested
    inner class 분봉_보관 {
        @Test
        fun `받은 분봉을 DB에 쌓는다`() {
            poll(fresh(), 100.0, 101.0)

            candles.findByMarketAndTradeDateBetweenOrderByMinuteAt(Market.KOSPI, open.toLocalDate(), open.toLocalDate()).map { it.toCandle().close } shouldBe listOf(100.0, 101.0)
        }

        /** 진행 중이던 분이 더 완성된 값으로 다시 오면 같은 행을 고친다 — (시장, 분)이 유일 키다. */
        @Test
        fun `같은 분이 다시 오면 덮어쓴다`() {
            val detector = fresh()
            poll(detector, 100.0)
            now = open

            poll(detector, 105.5)

            candles.findAll().map { it.toCandle().close } shouldBe listOf(105.5)
        }

        @Test
        fun `재시작해도 그날 쌓은 분봉으로 이평을 이어 낸다`() {
            poll(fresh(), *DoubleArray(20) { 100.0 }, 99.0)

            poll(fresh(), 102.0, 100.0)

            fired("MA_REBOUND") shouldBe 1
        }
    }
}
