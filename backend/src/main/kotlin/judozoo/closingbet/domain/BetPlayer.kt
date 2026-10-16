package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/** 닉네임. 이메일은 `app_user`에만 있고 여기엔 [userId]만 둔다(feedback과 같은 이유). */
@Entity
@Table(name = "bet_player")
class BetPlayer(
    @Id @Column(name = "user_id") val userId: Long,
    nickname: String,
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    var nickname: String = nickname
        protected set

    /** 직접 바꾼 시각. 처음 받은 랜덤 닉네임이면 null — 바로 바꿀 수 있다 */
    @Column(name = "renamed_at")
    var renamedAt: LocalDateTime? = null
        protected set

    val nextRenameAt: LocalDateTime? get() = Nicknames.nextRenameAt(renamedAt)

    fun rename(nick: String, at: LocalDateTime) {
        nickname = nick
        renamedAt = at
    }
}
