package judozoo.platform.kis

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KisHolidayTest {
    private val stub = KisStub()
    private val holiday = KisHoliday(stub.client)
    private val base = LocalDate.of(2026, 9, 11)

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 휴장일 {
        @Test
        fun `개장일만 모은다`() {
            stub.respond(
                URL,
                """{"rt_cd":"0","output":[{"bass_dt":"20260911","opnd_yn":"Y"},{"bass_dt":"20260912","opnd_yn":"N"},
                {"bass_dt":"20260914","opnd_yn":"Y"}]}""",
            )

            holiday.fetchOpenDays(base) shouldBe setOf(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14))
        }

        /** 호출측이 주말만으로 폴백하게 한다. */
        @Test
        fun `오류면 빈 집합`() {
            stub.respond(URL, """{"rt_cd":"1","msg1":"오류"}""")

            holiday.fetchOpenDays(base).shouldBeEmpty()
        }

        @Test
        fun `연결이 끊기면 빈 집합`() {
            stub.fail(URL)

            holiday.fetchOpenDays(base).shouldBeEmpty()
        }
    }

    companion object {
        private const val URL = "/uapi/domestic-stock/v1/quotations/chk-holiday"
    }
}
