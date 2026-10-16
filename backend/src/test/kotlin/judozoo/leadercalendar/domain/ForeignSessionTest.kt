package judozoo.leadercalendar.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ForeignSessionTest {
    @Nested
    inner class 직전_해외장 {
        @Test
        fun `화요일 칸에는 월요일 해외장이 붙는다`() {
            ForeignSession.pairedWith(LocalDate.of(2026, 9, 22)) shouldBe LocalDate.of(2026, 9, 21)
        }

        @Test
        fun `월요일 칸에는 금요일 해외장이 붙는다`() {
            ForeignSession.pairedWith(LocalDate.of(2026, 9, 28)) shouldBe LocalDate.of(2026, 9, 25)
        }

        @Test
        fun `달의 첫날 칸은 전달의 해외장을 본다`() {
            ForeignSession.pairedWith(LocalDate.of(2026, 9, 1)) shouldBe LocalDate.of(2026, 8, 31)
        }
    }
}
