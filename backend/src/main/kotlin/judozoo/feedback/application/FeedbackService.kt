package judozoo.feedback.application

import judozoo.auth.domain.CurrentUser
import judozoo.feedback.domain.Feedback
import judozoo.feedback.domain.FeedbackRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** 의견 접수, 그리고 탈퇴한 사람의 의견 지우기. */
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

    /** 탈퇴한 사람이 보낸 의견을 지운다(처리방침상 수집 항목). 가입자 행과 한 트랜잭션이어야 해서 바깥 트랜잭션에 얹힌다. */
    @Transactional
    fun eraseBy(userId: Long): Int = feedbacks.deleteByUserId(userId)
}
