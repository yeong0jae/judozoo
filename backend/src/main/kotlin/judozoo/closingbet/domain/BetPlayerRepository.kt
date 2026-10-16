package judozoo.closingbet.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BetPlayerRepository : JpaRepository<BetPlayer, Long> {
    fun existsByNickname(nickname: String): Boolean

    @Query("select p.nickname from BetPlayer p")
    fun nicknames(): Set<String>
}
