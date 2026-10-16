package judozoo.leadertimeline.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.leadertimeline.application.LeaderTimelineService
import judozoo.leadertimeline.application.LeaderTimelineService.RecordedTick
import judozoo.leadertimeline.domain.LeaderTickStock
import judozoo.market.domain.Region
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
import java.time.LocalDateTime

@WebMvcTest(LeaderTimelineController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class LeaderTimelineControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var service: LeaderTimelineService

    private fun stock(rank: Int, code: String, name: String, rate: Float) =
        LeaderTickStock(1, rank, null, code, name, BigDecimal("1000"), rate, BigDecimal("100000000000"))

    private fun minute(m: Int, s: Int = 0) = LocalDateTime.of(2026, 9, 23, 10, m, s)

    private fun day() {
        every { service.findDay(any(), any(), any()) } returns listOf(
            RecordedTick(minute(15), minute(15, 1), listOf(stock(1, "000660", "SK하이닉스", 2.2f), stock(2, "005930", "삼성전자", 0.7f))),
            RecordedTick(minute(16), minute(16, 1), listOf(stock(1, "005930", "삼성전자", 0.8f))),
            RecordedTick(minute(17), minute(17, 1), emptyList()),
        )
    }

    @Nested
    inner class 주도주_타임라인_API {
        /** 로그인 없이 본다. 하루가 700분 × 5종목이라 종목은 사전으로 한 번만 싣는다. */
        @Test
        fun `종목은 사전으로 한 번만 싣고 분마다 번호로 준다`() {
            day()

            mvc.get("/api/leader-timeline") { param("market", "kr"); param("date", "2026-09-23") }.andExpect {
                status { isOk() }
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":{
                        "stocks":[{"exchange":null,"code":"000660","name":"SK하이닉스"},{"exchange":null,"code":"005930","name":"삼성전자"}],
                        "ticks":[{"at":"10:15","stocks":[0,1],"rates":[2.2,0.7],"values":[1.0E11,1.0E11]},
                                 {"at":"10:16","stocks":[1],"rates":[0.8],"values":[1.0E11]},
                                 {"at":"10:17","stocks":[],"rates":[],"values":[]}],
                        "lastTakenAt":"2026-09-23T10:17:01"}}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `시장과 since를 그대로 넘긴다`() {
            day()

            mvc.get("/api/leader-timeline") { param("market", "us"); param("date", "2026-09-23"); param("since", "10:59") }

            verify { service.findDay(Region.US, LocalDate.of(2026, 9, 23), LocalDateTime.of(2026, 9, 23, 10, 59)) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["market=jp&date=2026-09-23", "market=kr&date=2026-9-23", "market=kr", "market=kr&date=2026-09-23&since=25:00"])
        fun `모양이 틀린 요청은 거절한다`(query: String) {
            day()

            mvc.get("/api/leader-timeline?$query").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_PARAMETER") }
            }
        }
    }
}
