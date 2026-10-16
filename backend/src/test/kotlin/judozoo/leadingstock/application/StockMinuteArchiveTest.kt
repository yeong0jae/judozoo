package judozoo.leadingstock.application

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import judozoo.IntegrationTest
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.StockMinuteCandleRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate

@IntegrationTest
class StockMinuteArchiveTest @Autowired constructor(
    private val repository: StockMinuteCandleRepository,
    private val transaction: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {
    private val day = LocalDate.of(2026, 9, 25)
    private var ticks = 0L

    private fun fresh() = StockMinuteArchive(repository, transaction) { ticks }

    private fun bar(on: LocalDate, hour: Int, minute: Int = 0, close: Long = 100) = MinuteCandle(on.atTime(hour, minute), close, close + 1, close - 1, close, 10, close * 10)

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM stock_minute_candle")
    }

    @Nested
    inner class 보관 {
        @Test
        fun `넣은 날을 날짜로 꺼낸다`() {
            val archive = fresh()
            archive.put("005930", day, listOf(bar(day, 9, 1), bar(day, 9, 0)))

            archive.get("005930", day)!!.map { it.dateTime.minute } shouldBe listOf(0, 1)
        }

        @Test
        fun `랭킹 코드와 맨 코드는 같은 종목이다`() {
            val archive = fresh()
            archive.put("005930_AL", day, listOf(bar(day, 9)))

            archive.get("005930", day)!!.size shouldBe 1
        }

        @Test
        fun `빈 날은 넣지 않는다`() {
            val archive = fresh()
            archive.put("005930", day, emptyList())

            archive.get("005930", day).shouldBeNull()
        }

        @Test
        fun `메모리 수명이 지나면 DB에서 다시 올린다`() {
            val archive = fresh()
            archive.put("005930", day, listOf(bar(day, 9)))
            jdbc.update("DELETE FROM stock_minute_candle")
            ticks += Duration.ofDays(5).toNanos()

            archive.get("005930", day).shouldBeNull()
        }
    }

    @Nested
    inner class DB {
        @Test
        fun `메모리가 비어도 DB에서 그날 봉을 꺼낸다`() {
            fresh().put("005930", day, listOf(bar(day, 9, close = 71_500), bar(day, 9, 1)))

            val bars = fresh().get("005930", day)!!

            bars.map { it.dateTime.minute } shouldBe listOf(0, 1)
            bars[0] shouldBe bar(day, 9, close = 71_500)
        }

        @Test
        fun `같은 날을 다시 넣으면 통째로 갈아 끼운다`() {
            fresh().put("005930", day, listOf(bar(day, 9), bar(day, 9, 1)))
            fresh().put("005930", day, listOf(bar(day, 9, close = 200)))

            fresh().get("005930", day)!!.map { it.closePrice } shouldBe listOf(200L)
        }

        @Test
        fun `DB에 없는 날은 없다고 답한다`() {
            fresh().get("005930", day).shouldBeNull()
        }
    }

    @Test
    fun `DB가 죽어 있으면 메모리만으로 넣고 꺼낸다`() {
        val broken = mockk<StockMinuteCandleRepository> {
            every { findByStockCodeAndTradeDateOrderByMinuteAt(any(), any()) } throws IllegalStateException("DB 다운")
            every { deleteDay(any(), any()) } throws IllegalStateException("DB 다운")
        }
        val archive = StockMinuteArchive(broken, transaction)

        archive.get("005930", day).shouldBeNull()
        archive.put("005930", day, listOf(bar(day, 9)))
        archive.get("005930", day)!!.size shouldBe 1
    }

    @Test
    fun `키움 페이지의 가장 이른 날은 잘려 있어 빼고 넣는다`() {
        val archive = fresh()
        val earlier = day.minusDays(1)

        archive.putPage("005930", listOf(bar(day, 9), bar(earlier, 19)))

        archive.get("005930", day)!!.size shouldBe 1
        archive.get("005930", earlier).shouldBeNull()
    }
}
