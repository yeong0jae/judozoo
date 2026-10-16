package judozoo.leadingstock.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.leadingstock.application.LeadingStockService.SignalReading
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.MinuteCandles.VolumeSpike
import judozoo.leadingstock.domain.SignalEvent
import judozoo.leadingstock.domain.SignalEventRepository
import judozoo.leadingstock.domain.SpikeDirection
import judozoo.library.KST
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class StockSignalDetectorTest {
    private val at = LocalDateTime.of(2026, 9, 11, 10, 0)
    private var now = at
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val stock = LeadingStockSnapshot("005930", "삼성전자", 70_000, 5.0, 1, 1_000_000_000)
    private var ratio: Double? = 5.0
    private val service = mockk<LeadingStockService> {
        every { signalReadings() } answers {
            listOf(SignalReading(stock, ratio?.let { VolumeSpike(2_000_000_000, now, it, SpikeDirection.BUY) }))
        }
    }
    private val saved = mutableListOf<SignalEvent>()
    private val events = mockk<SignalEventRepository> { every { saveAll(any<List<SignalEvent>>()) } answers { firstArg<List<SignalEvent>>().also { saved += it } } }
    private val detector = StockSignalDetector(service, events, clock)

    @Nested
    inner class 종목_시그널_폴러 {
        @Test
        fun `전이가 일어나면 스파이크 행을 적재한다`() {
            detector.detect()

            saved.size shouldBe 1
            saved[0].run {
                eventType shouldBe "VOLUME_SPIKE"
                (occurredAt to tradeDate) shouldBe (at to at.toLocalDate())
                (spikeRatio to minuteTradingValue) shouldBe (5.0 to 2_000_000_000L)
                spikeDirection shouldBe SpikeDirection.BUY
            }
        }

        @Test
        fun `스파이크 행에는 이평과 돌파 갭이 비어 있다`() {
            detector.detect()

            (saved[0].ma to saved[0].gapRate) shouldBe (null to null)
        }

        @Test
        fun `전이가 없으면 아무것도 적재하지 않는다`() {
            ratio = null

            detector.detect()

            saved.shouldBeEmpty()
        }

        @Test
        fun `쿨다운 안에 같은 전이가 또 뜨면 건너뛴다`() {
            detector.detect()
            detector.forgetStates()
            now = at.plusMinutes(1)

            detector.detect()

            saved.size shouldBe 1
        }

        @Test
        fun `쿨다운이 지나면 다시 적재한다`() {
            detector.detect()
            detector.forgetStates()
            now = at.plusMinutes(5)

            detector.detect()

            saved.size shouldBe 2
        }

        @Test
        fun `일자가 바뀌면 직전 상태를 버린다`() {
            detector.detect()
            now = at.plusDays(1)

            detector.detect()

            saved.size shouldBe 2
        }
    }
}
