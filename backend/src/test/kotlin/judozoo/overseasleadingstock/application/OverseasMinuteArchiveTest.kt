package judozoo.overseasleadingstock.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.IntegrationTest
import judozoo.overseasleadingstock.domain.DayCandles
import judozoo.overseasleadingstock.domain.OverseasMinuteCandle
import judozoo.overseasleadingstock.domain.OverseasMinuteCandleRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@IntegrationTest
class OverseasMinuteArchiveTest @Autowired constructor(
    private val repository: OverseasMinuteCandleRepository,
    private val transaction: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {
    private fun fresh() = OverseasMinuteArchive(repository, transaction)

    private fun bar(day: LocalDate, kst: LocalDateTime, close: Double = 100.5) =
        OverseasMinuteCandle(kst, day, close, close + 1, close - 1, close, 10, close * 10)

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM overseas_minute_candle")
    }

    @Nested
    inner class 해외_지난_날_분봉_DB {
        @Test
        fun `메모리가 비어도 최근 날부터 꺼낸다`() {
            val dayBefore = LocalDate.of(2026, 9, 24)
            val yesterday = LocalDate.of(2026, 9, 25)
            fresh().apply {
                put("NAS", "AAPL", dayBefore, listOf(bar(dayBefore, LocalDateTime.of(2026, 9, 25, 4, 59))))
                put("NAS", "AAPL", yesterday, listOf(bar(yesterday, LocalDateTime.of(2026, 9, 26, 0, 1)), bar(yesterday, LocalDateTime.of(2026, 9, 25, 23, 59))))
            }

            val days = fresh().recent("NAS", "AAPL", 2)

            days.map { it.day } shouldBe listOf(yesterday, dayBefore)
            days[0].candles.map { it.dateTime.hour } shouldBe listOf(23, 0)
            days[0].candles[0].close shouldBe 100.5
            days[0].candles[0].tradingValue shouldBe 1005.0
        }

        @Test
        fun `단정밀도로 저장된 소수도 DB에 보이는 값으로 읽는다`() {
            val day = LocalDate.of(2026, 9, 25)
            fresh().put("NAS", "AAPL", day, listOf(bar(day, LocalDateTime.of(2026, 9, 25, 23, 0), close = 230.17)))

            fresh().recent("NAS", "AAPL", 1)[0].candles[0].close shouldBe 230.17
        }

        @Test
        fun `다른 종목의 날은 섞이지 않는다`() {
            val day = LocalDate.of(2026, 9, 25)
            fresh().put("NAS", "AAPL", day, listOf(bar(day, LocalDateTime.of(2026, 9, 25, 23, 0))))

            fresh().recent("NAS", "MSFT", 2).shouldBeEmpty()
        }

        @Test
        fun `같은 날을 다시 넣으면 통째로 갈아 끼운다`() {
            val day = LocalDate.of(2026, 9, 25)
            fresh().apply {
                put("NAS", "AAPL", day, listOf(bar(day, LocalDateTime.of(2026, 9, 25, 23, 0), 100.0)))
                put("NAS", "AAPL", day, listOf(bar(day, LocalDateTime.of(2026, 9, 25, 23, 0), 105.0)))
            }

            fresh().recent("NAS", "AAPL", 2).single().candles.map { it.close } shouldBe listOf(105.0)
        }

        @Test
        fun `기준일 전의 날을 지운다`() {
            val old = LocalDate.of(2026, 9, 10)
            val yesterday = LocalDate.of(2026, 9, 25)
            fresh().apply {
                put("NAS", "AAPL", old, listOf(bar(old, LocalDateTime.of(2026, 9, 10, 23, 0))))
                put("NAS", "AAPL", yesterday, listOf(bar(yesterday, LocalDateTime.of(2026, 9, 25, 23, 0))))
                purge(LocalDate.of(2026, 9, 15))
            }

            fresh().recent("NAS", "AAPL", 5).map { it.day } shouldBe listOf(yesterday)
        }
    }

    @Nested
    inner class 보관소_장애 {
        @Test
        fun `DB가 안 되어도 완성된 지난 봉은 메모리에서 읽는다`() {
            val broken = mockk<OverseasMinuteCandleRepository> { every { latestDays(any(), any(), any()) } throws IllegalStateException("DB 끊김") }
            val brokenTx = mockk<TransactionTemplate> { every { executeWithoutResult(any()) } throws IllegalStateException("DB 끊김") }
            val archive = OverseasMinuteArchive(broken, brokenTx)
            val day = LocalDate.of(2026, 9, 25)
            val bars = listOf(bar(day, LocalDateTime.of(2026, 9, 25, 23, 0)))

            archive.put("NAS", "AAPL", day, bars)

            archive.recent("NAS", "AAPL", 2) shouldBe listOf(DayCandles(day, bars))
        }
    }
}
