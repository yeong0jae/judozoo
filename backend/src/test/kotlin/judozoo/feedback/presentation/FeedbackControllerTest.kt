package judozoo.feedback.presentation

import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.Cookie
import judozoo.LoggedIn
import judozoo.auth.domain.CurrentUser
import judozoo.feedback.application.FeedbackService
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(FeedbackController::class)
@Import(LoggedIn.ClockAtSigning::class)
@TestPropertySource(properties = [LoggedIn.SECRET])
class FeedbackControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockkBean
    private lateinit var feedback: FeedbackService

    private val loggedIn = LoggedIn.cookie

    private fun send(content: String, cookie: Cookie? = loggedIn) = mvc.post("/api/feedback") {
        contentType = MediaType.APPLICATION_JSON
        this.content = """{"content":"${content.replace("\"", "\\\"")}"}"""
        cookie?.let { cookie(it) }
    }

    @Nested
    inner class 로그인_관문 {
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
                status { isOk() }
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
