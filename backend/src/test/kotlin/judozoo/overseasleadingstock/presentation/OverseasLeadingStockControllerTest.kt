package judozoo.overseasleadingstock.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.overseasleadingstock.application.OverseasLeadingStockService
import judozoo.overseasleadingstock.domain.FilterResult
import judozoo.overseasleadingstock.domain.OverseasMinuteCandle
import judozoo.overseasleadingstock.domain.OverseasStockRank
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.LocalDateTime

@WebMvcTest(OverseasLeadingStockController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class OverseasLeadingStockControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var service: OverseasLeadingStockService

    private val aaa = OverseasStockRank(1, "NAS", "AAA", "AAA", "SOME CORP", 100.0, 5.0, 10.0, 1000.0)

    @Nested
    inner class 해외_주도주_API {
        @Test
        fun `랭킹 응답 형식이 기존 계약과 같다`() {
            every { service.candidates(5.0) } returns listOf(aaa)

            mvc.get("/api/overseas-leading-stocks/candidates") { param("minChangeRate", "5") }.andExpect {
                status { isOk() }
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":[{"rank":1,"exchange":"NAS","symbol":"AAA","name":"AAA",
                        "ename":"SOME CORP","price":100.0,"diff":5.0,"rate":10.0,"tradingValue":1000.0}]}""",
                        org.springframework.test.json.JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `등락률 인자는 허용 범위로 잘라 쓴다`() {
            every { service.candidates(any()) } returns emptyList()

            mvc.get("/api/overseas-leading-stocks/candidates") { param("minChangeRate", "99") }
            mvc.get("/api/overseas-leading-stocks/candidates") { param("minChangeRate", "-99") }
            mvc.get("/api/overseas-leading-stocks/candidates")

            verify(exactly = 2) { service.candidates(7.0) }
            verify(exactly = 1) { service.candidates(-12.0) }
        }

        @Test
        fun `잘못된 인자는 400으로 돌려준다`() {
            mvc.get("/api/overseas-leading-stocks/candidates") { param("minChangeRate", "abc") }.andExpect {
                status { isBadRequest() }
                content { json("""{"code":"INVALID_PARAMETER","status":400,"data":null}""") }
            }
        }

        @Test
        fun `주도주는 다섯 종목이다`() {
            every { service.leaders(5) } returns listOf(aaa)

            mvc.get("/api/overseas-leading-stocks/leaders").andExpect { jsonPath("$.data[0].symbol") { value("AAA") } }
        }

        @Test
        fun `거래소와 심볼은 대문자로 맞춰 조회한다`() {
            every { service.evaluate("NAS", "AAA") } returns OverseasLeadingStockService.StockEvaluation(
                aaa, null, listOf(FilterResult("거래대금순위", "통합 상위 40위 이내", "1위", true, 1.0, 40.0)),
            )

            mvc.get("/api/overseas-leading-stocks/nas/aaa") { cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data.symbol") { value("AAA") }
                jsonPath("$.data.marketCap") { value(null as Any?) }
                jsonPath("$.data.filterResults[0].filterName") { value("거래대금순위") }
                jsonPath("$.data.filterResults[0].value") { value(1.0) }
            }
        }

        @Test
        fun `종목 상세는 로그인 뒤다`() {
            mvc.get("/api/overseas-leading-stocks/NAS/AAA").andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `분봉 시각은 한국 벽시계를 초까지 적는다`() {
            every { service.minuteCandles("NAS", "AAA") } returns listOf(
                OverseasMinuteCandle(LocalDateTime.of(2026, 8, 25, 22, 30), LocalDate.of(2026, 8, 25), 99.0, 101.0, 98.0, 100.0, 1000, 99000.0),
            )

            mvc.get("/api/overseas-leading-stocks/NAS/AAA/minute-candles") { cookie(LoggedIn.cookie) }.andExpect {
                jsonPath("$.data[0].time") { value("2026-08-25T22:30:00") }
                jsonPath("$.data[0].tradingValue") { value(99000.0) }
            }
        }
    }
}
