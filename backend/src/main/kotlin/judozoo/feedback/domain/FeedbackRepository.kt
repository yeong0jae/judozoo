package judozoo.feedback.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface FeedbackRepository : JpaRepository<Feedback, Long> {
    @Modifying
    @Query("delete from Feedback f where f.userId = :userId")
    fun deleteByUserId(userId: Long): Int
}
