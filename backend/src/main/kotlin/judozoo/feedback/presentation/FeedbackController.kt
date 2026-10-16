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

@RestController
class FeedbackController(private val feedback: FeedbackService, private val clock: Clock) {
    data class FeedbackRequest(val content: String) {
        fun validContent(): String {
            val trimmed = content.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER")
            return trimmed
        }
    }

    @PostMapping("/api/feedback")
    fun send(@RequestBody body: FeedbackRequest, user: CurrentUser): ApiResponse<Nothing?> {
        feedback.receive(user, body.validContent(), LocalDateTime.now(clock.withZone(KST)))
        return ApiResponse.created(null)
    }

    companion object {
        private const val MAX_LENGTH = 500
    }
}
