package judozoo.leadingstock.application

import io.kotest.matchers.shouldBe
import judozoo.IntegrationTest
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.leadingstock.domain.WideLimitDayRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

/** 제한폭이 넓은 날(상장 첫날) 표시 — 한 번 +30%를 넘으면 그날 내내 환산한다. 재시작해도 잃지 않는다. */
@IntegrationTest
class WideLimitDaysTest @Autowired constructor(
    private val repository: WideLimitDayRepository,
    private val transaction: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {
    private val today = LocalDate.of(2026, 10, 1)
    private val at = today.atTime(10, 0)

    private fun fresh() = WideLimitDays(repository, transaction)

    private fun stock(code: String, rate: Double) = LeadingStockSnapshot(code, code, 1000, rate, 1, 1)

    private fun WideLimitDays.marked(vararg stocks: LeadingStockSnapshot, on: LocalDate = today) =
        mark(stocks.toList(), on, at).filter { it.wideLimitDay }.map { it.stockCode }

    @BeforeEach
    fun 빈_테이블() {
        jdbc.update("DELETE FROM wide_limit_day")
    }

    @Nested
    inner class 표시 {
        @Test
        fun `30퍼센트를 넘은 종목에 표시를 붙인다`() {
            fresh().marked(stock("A", 31.0), stock("B", 29.0)) shouldBe listOf("A")
        }

        @Test
        fun `한 번 넘었으면 내려와도 그날은 계속 붙는다`() {
            val days = fresh()
            days.marked(stock("A", 100.0))

            days.marked(stock("A", 26.0)) shouldBe listOf("A")
        }

        @Test
        fun `랭킹 코드와 맨 코드는 같은 종목이다`() {
            val days = fresh()
            days.marked(stock("468670_AL", 100.0))

            days.marked(stock("468670", 20.0)) shouldBe listOf("468670")
        }

        @Test
        fun `날이 바뀌면 새로 센다`() {
            val days = fresh()
            days.marked(stock("A", 100.0))

            days.marked(stock("A", 20.0), on = today.plusDays(1)) shouldBe emptyList()
        }
    }

    @Nested
    inner class 재시작 {
        @Test
        fun `재시작해도 오늘 넘은 종목을 기억한다`() {
            fresh().marked(stock("A", 100.0))

            fresh().marked(stock("A", 20.0)) shouldBe listOf("A")
        }

        /** Python이 먼저 넣은 행도 같은 표다 — 유니크 키에 걸리지 않게 이미 있는 종목은 넣지 않는다. */
        @Test
        fun `같은 종목을 여러 번 넘어도 한 번만 남긴다`() {
            jdbc.update("INSERT INTO wide_limit_day (trade_date, stock_code, created_at) VALUES (?, 'A', ?)", today, at)

            fresh().marked(stock("A", 100.0), stock("B", 100.0))
            fresh().marked(stock("B", 120.0))

            jdbc.queryForList("SELECT stock_code FROM wide_limit_day ORDER BY stock_code", String::class.java) shouldBe listOf("A", "B")
        }

        @Test
        fun `일주일 지난 기록은 지운다`() {
            jdbc.update("INSERT INTO wide_limit_day (trade_date, stock_code, created_at) VALUES (?, 'OLD', ?)", today.minusDays(8), at)
            jdbc.update("INSERT INTO wide_limit_day (trade_date, stock_code, created_at) VALUES (?, 'KEEP', ?)", today.minusDays(7), at)

            fresh().marked(stock("A", 1.0))

            jdbc.queryForList("SELECT stock_code FROM wide_limit_day", String::class.java) shouldBe listOf("KEEP")
        }
    }
}
