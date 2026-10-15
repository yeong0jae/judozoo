package judozoo.feedback.application

import judozoo.auth.domain.CurrentUser
import judozoo.feedback.domain.Feedback
import judozoo.feedback.domain.FeedbackRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 의견 접수.
 *
 * 탈퇴 때 의견을 지우는 일(`erase_by`)은 아직 옮기지 않았다 — 탈퇴는 auth와 함께 Python에 남아 있다(028).
 */
@Service
class FeedbackService(private val feedbacks: FeedbackRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장이 곧 접수다. 알림은 Grafana가 이 테이블을 보고 보낸다 — 앱이 웹훅을 들고 있으면
     * 시크릿이 늘고 전송 실패가 사용자 요청의 실패가 된다.
     */
    @Transactional
    fun receive(user: CurrentUser, content: String, now: LocalDateTime) {
        val saved = feedbacks.save(Feedback.write(user.id, content, now))
        // 알림이 안 오면 규칙이 문제인지 접수가 문제인지 여기서 갈린다
        log.info("의견 접수 — {}자", saved.content.length)
    }
}
