package judozoo.insight.presentation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import judozoo.LoggedIn
import judozoo.insight.application.InsightService
import judozoo.insight.domain.Source
import judozoo.insight.domain.StockReason
import judozoo.insight.domain.Trigger
import judozoo.insight.domain.Verdict
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.LocalDateTime

@WebMvcTest(InsightController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class InsightControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var service: InsightService

    private fun reason(explained: Boolean = true) = StockReason.made(
        Region.KR, LocalDate.of(2026, 9, 28), "011070", "LG이노텍", LocalDateTime.of(2026, 9, 28, 10, 41), Trigger.ENTRY,
        Verdict(
            published = true, explained = explained, keywords = if (explained) listOf("Aeva", "라이다") else emptyList(),
            reason = if (explained) "美 라이다 업체 Aeva 지분가치 상승" else "",
            evidence = if (explained) listOf(Source(1, "파이낸셜뉴스", "지분가치 상승 영향", "https://fnnews.com/1")) else emptyList(),
            related = listOf(Source(2, "재경일보", "호재성 뉴스 부재 속 급등", "https://jkn.co.kr/2")),
        ),
        "gemini-3.8-flash",
    )

    @Nested
    inner class 왜_오르나_API {
        @Test
        fun `방문자는 키워드와 사유까지 보고 기사는 건수만 받는다`() {
            every { service.reasons(Region.KR, any()) } returns listOf(reason())

            mvc.get("/api/insight/reasons") { param("market", "kr") }.andExpect {
                content {
                    json(
                        """{"code":"SUCCESS","status":200,"data":[{"code":"011070","explained":true,"keywords":["Aeva","라이다"],
                        "reason":"美 라이다 업체 Aeva 지분가치 상승","generatedAt":"2026-09-28T10:41:00",
                        "evidenceCount":1,"relatedCount":1,"evidence":null,"related":null}]}""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
        }

        @Test
        fun `로그인하면 근거와 관련 기사를 받는다`() {
            every { service.reasons(Region.KR, any()) } returns listOf(reason())

            mvc.get("/api/insight/reasons") { param("market", "kr"); cookie(LoggedIn.cookie) }.andExpect {
                content {
                    json(
                        """{"data":[{"evidence":[{"source":"파이낸셜뉴스","title":"지분가치 상승 영향","url":"https://fnnews.com/1"}],
                        "related":[{"source":"재경일보","title":"호재성 뉴스 부재 속 급등","url":"https://jkn.co.kr/2"}]}]}""",
                        JsonCompareMode.LENIENT,
                    )
                }
            }
        }

        @Test
        fun `설명 없음도 관련 기사 건수와 함께 내려간다`() {
            every { service.reasons(Region.KR, any()) } returns listOf(reason(explained = false))

            mvc.get("/api/insight/reasons") { param("market", "kr") }.andExpect {
                jsonPath("$.data[0].explained") { value(false) }
                jsonPath("$.data[0].reason") { value("") }
                jsonPath("$.data[0].relatedCount") { value(1) }
            }
        }

        @Test
        fun `해외는 us로 조회한다`() {
            every { service.reasons(any(), any()) } returns emptyList()

            mvc.get("/api/insight/reasons") { param("market", "us") }

            verify { service.reasons(Region.US, any()) }
        }

        @Test
        fun `시장이 틀리면 거절한다`() {
            mvc.get("/api/insight/reasons") { param("market", "jp") }.andExpect { jsonPath("$.code") { value("INVALID_PARAMETER") } }
        }
    }
}
