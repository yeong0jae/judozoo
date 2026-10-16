package judozoo.platform.kiwoom

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SignedNumberTest {
    @Nested
    inner class 부호_파서 {
        /** 후위 부호("1.23-")가 실제로 온다 — 그냥 숫자로 바꾸면 0으로 떨어진다. */
        @Test
        fun `실수 부호 변종을 흡수한다`() {
            mapOf("+1.23" to 1.23, "-1.23" to -1.23, "1.23-" to -1.23, "1.23" to 1.23, "" to 0.0, null to 0.0, "abc" to 0.0)
                .forEach { (input, expected) -> input.toSignedDouble() shouldBe expected }
        }

        @Test
        fun `정수 부호 변종을 흡수한다`() {
            mapOf("+255" to 255L, "-622" to -622L, "1234-" to -1234L, "0" to 0L, "" to 0L, null to 0L)
                .forEach { (input, expected) -> input.toSignedLong() shouldBe expected }
        }
    }
}
