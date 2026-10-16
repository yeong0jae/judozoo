package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "bet_player")
class BetPlayer(
    @Id @Column(name = "user_id") val userId: Long,
    nickname: String,
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    var nickname: String = nickname
        protected set

    @Column(name = "renamed_at")
    var renamedAt: LocalDateTime? = null
        protected set

    val nextRenameAt: LocalDateTime? get() = Nicknames.nextRenameAt(renamedAt)

    fun rename(nick: String, at: LocalDateTime) {
        nickname = nick
        renamedAt = at
    }
}
