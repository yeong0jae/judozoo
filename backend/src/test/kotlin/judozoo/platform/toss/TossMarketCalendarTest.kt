package judozoo.platform.toss

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TossMarketCalendarTest {
    private val stub = TossStub()
    private val calendar = TossMarketCalendar(stub.client)
    private val day = LocalDate.of(2026, 9, 11)
    private val krOpen = """{"result":{"today":{"integrated":{"regularMarket":{"startTime":"09:00"}}}}}"""

    @AfterEach
    fun 정리() = stub.close()

    @Nested
    inner class 액세스_토큰 {
        @Test
        fun `401이면 토큰을 버리고 한 번 재시도한다`() {
            stub.respondInOrder(KR, 401 to "{}", 200 to krOpen)

            calendar.isTradingDay("KR", day) shouldBe true
            stub.invalidated shouldBe 1
        }
    }

    @Nested
    inner class 장_운영_판정 {
        @Test
        fun `국내는 통합 세션이 있으면 개장`() {
            stub.respond(KR, krOpen)

            calendar.isTradingDay("KR", day) shouldBe true
        }

        @Test
        fun `국내 세션이 전부 비면 휴장`() {
            stub.respond(KR, """{"result":{"today":{"integrated":{"regularMarket":null,"preMarket":null}}}}""")

            calendar.isTradingDay("KR", day) shouldBe false
        }

        @Test
        fun `해외는 네 세션 중 하나라도 있으면 개장`() {
            stub.respond(US, """{"result":{"today":{"preMarket":{"startTime":"17:00"},"regularMarket":null}}}""")

            calendar.isTradingDay("US", day) shouldBe true
        }

        @Test
        fun `해외 네 세션이 전부 비면 휴장`() {
            stub.respond(US, """{"result":{"today":{"dayMarket":null,"preMarket":null,"regularMarket":null,"afterMarket":null}}}""")

            calendar.isTradingDay("US", day) shouldBe false
        }

        @Test
        fun `조회 실패면 null`() {
            stub.respond(KR, "{}", status = 500)

            calendar.isTradingDay("KR", day).shouldBeNull()
        }

        @Test
        fun `연결이 끊기면 null`() {
            stub.fail(KR)

            calendar.isTradingDay("KR", day).shouldBeNull()
        }
    }

    companion object {
        private const val KR = "/api/v1/market-calendar/KR"
        private const val US = "/api/v1/market-calendar/US"
    }
}
