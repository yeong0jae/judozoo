package judozoo.feedback.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "feedback")
class Feedback private constructor(
    @Column(name = "user_id")
    val userId: Long?,
    @Column(columnDefinition = "text")
    val content: String,
    @Column(name = "created_at")
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        fun write(userId: Long?, content: String, now: LocalDateTime) = Feedback(userId, content.trim(), now)
    }
}
