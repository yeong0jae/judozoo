package judozoo.feedback.presentation

import judozoo.auth.domain.CurrentUser
import judozoo.feedback.application.FeedbackService
import judozoo.library.KST
import judozoo.library.web.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDateTime

/** 의견 보내기 — 서비스 안에서 한마디 남기는 유일한 경로. */
@RestController
class FeedbackController(private val feedback: FeedbackService, private val clock: Clock) {

    /** 공백만 보낸 것은 빈 것과 같다 — 먼저 털고 나서 길이를 본다. 500자는 화면과 같은 상한이다. */
    data class FeedbackRequest(val content: String) {
        fun validContent(): String {
            val trimmed = content.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER")
            return trimmed
        }
    }

    /** 관문이 이미 막지만 보낸 사람이 필요해 한 번 더 받는다. */
    @PostMapping("/api/feedback")
    fun send(@RequestBody body: FeedbackRequest, user: CurrentUser): ApiResponse<Nothing?> {
        feedback.receive(user, body.validContent(), LocalDateTime.now(clock.withZone(KST)))
        return ApiResponse.created(null)
    }

    companion object {
        private const val MAX_LENGTH = 500
    }
}
