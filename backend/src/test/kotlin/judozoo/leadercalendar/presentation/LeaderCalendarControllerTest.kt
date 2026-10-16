package judozoo.leadercalendar.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.leadercalendar.application.LeaderCalendarService
import judozoo.leadercalendar.application.LeaderCalendarService.LeaderStock
import judozoo.leadercalendar.application.LeaderCalendarService.RecordedDay
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.time.LocalDate

@WebMvcTest(LeaderCalendarController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class LeaderCalendarControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var service: LeaderCalendarService

    private fun month(domestic: List<RecordedDay> = emptyList(), overseas: List<RecordedDay> = emptyList()) {
        every { service.findMonth(any(), any()) } returns (domestic to overseas)
    }

    @Nested
    inner class 주도주_캘린더_API {
        @Test
        fun `요청한 달로 조회한다`() {
            month()

            mvc.get("/api/leader-calendar") { param("month", "2026-09") }.andExpect { jsonPath("$.code") { value("SUCCESS") } }

            verify { service.findMonth(2026, 9) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["2026-9", "2026-13", "2026-00", "202609", ""])
        fun `달 모양이 틀리면 거절한다`(value: String) {
            month()

            mvc.get("/api/leader-calendar") { param("month", value) }.andExpect { jsonPath("$.code") { value("INVALID_PARAMETER") } }
        }

        @Test
        fun `국내와 해외를 따로 날짜별로 Python과 같은 모양으로 준다`() {
            val hynix = LeaderStock(1, null, "000660", "SK하이닉스", BigDecimal("250000.0000"), 2.2, BigDecimal("971000000000.00"))
            month(
                domestic = listOf(
                    RecordedDay(LocalDate.of(2026, 9, 23), listOf(hynix)),
                    RecordedDay(LocalDate.of(2026, 9, 14), emptyList()),
                    RecordedDay(LocalDate.of(2026, 9, 24), emptyList(), closed = true),
                    RecordedDay(LocalDate.of(2026, 9, 25), emptyList(), live = true),
                ),
            )

            mvc.get("/api/leader-calendar") { param("month", "2026-09") }.andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":{"overseas":[],"domestic":[
                        {"date":"2026-09-23","closed":false,"live":false,"stocks":[{"rank":1,"exchange":null,"code":"000660","name":"SK하이닉스",
                          "price":250000.0,"changeRate":2.2,"tradingValue":9.71E11}]},
                        {"date":"2026-09-14","closed":false,"live":false,"stocks":[]},
                        {"date":"2026-09-24","closed":true,"live":false,"stocks":[]},
                        {"date":"2026-09-25","closed":false,"live":true,"stocks":[]}]}}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }
    }
}
