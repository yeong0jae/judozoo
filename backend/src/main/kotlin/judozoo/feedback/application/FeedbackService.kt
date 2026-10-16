package judozoo.feedback.application

import judozoo.auth.domain.CurrentUser
import judozoo.feedback.domain.Feedback
import judozoo.feedback.domain.FeedbackRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class FeedbackService(private val feedbacks: FeedbackRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun receive(user: CurrentUser, content: String, now: LocalDateTime) {
        val saved = feedbacks.save(Feedback.write(user.id, content, now))
        log.info("의견 접수 — {}자", saved.content.length)
    }

    @Transactional
    fun eraseBy(userId: Long): Int = feedbacks.deleteByUserId(userId)
}
