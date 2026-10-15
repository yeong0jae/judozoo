package judozoo.feedback.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 사용자가 보낸 의견 한 건.
 *
 * **누가 썼는지는 `user_id`로만 남긴다** — 이메일까지 적으면 개인정보가 두 테이블에 흩어진다.
 * FK는 걸지 않는다(V006). 옛 세션에서 받은 행은 `user_id`가 비어 있다.
 * 의견 알림(Grafana)이 이 테이블을 직접 읽는다 — 컬럼을 바꾸면 알림 규칙도 같이 고친다.
 */
@Entity
@Table(name = "feedback")
class Feedback private constructor(
    @Column(name = "user_id")
    val userId: Long?,
    @Column(columnDefinition = "text")
    val content: String,
    /** KST 벽시계 */
    @Column(name = "created_at")
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        /** 앞뒤 공백은 여기서 턴다 — 저장된 뒤에 털면 알림 문구와 저장값이 어긋난다. */
        fun write(userId: Long?, content: String, now: LocalDateTime) = Feedback(userId, content.trim(), now)
    }
}
