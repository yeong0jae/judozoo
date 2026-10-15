package judozoo.feedback.presentation

import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.Cookie
import judozoo.auth.domain.CurrentUser
import judozoo.auth.presentation.LoginGateFilter
import judozoo.feedback.application.FeedbackService
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@WebMvcTest(FeedbackController::class)
@Import(FeedbackControllerTest.FixedClock::class)
@TestPropertySource(properties = ["judozoo.session-secret=test-secret"])
class FeedbackControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var feedback: FeedbackService

    /** Python이 secret `test-secret`으로 발급한 쿠키(사용자 id 7)가 살아 있는 시각. */
    @TestConfiguration
    class FixedClock {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.ofEpochSecond(1_792_070_321), ZoneOffset.UTC)
    }

    private val loggedIn = Cookie(
        LoginGateFilter.COOKIE_NAME,
        "eyJ1c2VyIjogeyJpZCI6IDcsICJlbWFpbCI6ICJhQGV4YW1wbGUuY29tIn0sICJfc3RhdGVfZ29vZ2xlX3giOiB7ImRhdGEiOiAxfX0=" +
            ".atDSsQ._chmEutnIautJuu1n_0keYIpTb0",
    )

    private fun send(content: String, cookie: Cookie? = loggedIn) = mvc.post("/api/feedback") {
        contentType = MediaType.APPLICATION_JSON
        this.content = """{"content":"${content.replace("\"", "\\\"")}"}"""
        cookie?.let { cookie(it) }
    }

    @Nested
    inner class 로그인_관문 {
        /** 허용목록에 없는 `/api` 경로라 관문이 먼저 막는다. */
        @Test
        fun `로그인하지 않으면 막힌다`() {
            send("여기 좋네요", cookie = null).andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
        }
    }

    @Nested
    inner class 접수 {
        @Test
        fun `보낸 사람과 함께 넘긴다`() {
            val user = slot<CurrentUser>()
            val content = slot<String>()
            every { feedback.receive(capture(user), capture(content), any()) } just runs

            send("차트가 느려요").andExpect {
                status { isOk() } // HTTP는 200, 201은 봉투 안에만
                jsonPath("$.status") { value(201) }
            }

            user.captured.id shouldBe 7
            content.captured shouldBe "차트가 느려요"
        }

        @Test
        fun `빈 의견은 받지 않는다`() {
            send("   ").andExpect { jsonPath("$.code") { value("INVALID_PARAMETER") } }

            verify(exactly = 0) { feedback.receive(any(), any(), any()) }
        }

        @Test
        fun `너무 긴 의견은 받지 않는다`() {
            send("가".repeat(501)).andExpect { jsonPath("$.code") { value("INVALID_PARAMETER") } }

            verify(exactly = 0) { feedback.receive(any(), any(), any()) }
        }

        @Test
        fun `본문 모양이 틀리면 받지 않는다`() {
            mvc.post("/api/feedback") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"필드 이름이 다르다"}"""
                cookie(loggedIn)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_PARAMETER") }
            }
        }
    }
}
