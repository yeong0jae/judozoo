package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "bet_round")
class BetRound(
    @Id @Column(name = "trading_day") val tradingDay: LocalDate,
    @Column(name = "snapped_at") val snappedAt: LocalDateTime,
    @Column(name = "market", columnDefinition = "json") val marketJson: String,
    val players: Int,
    @Column(name = "pot_man") val potMan: Long,
) {
    @Column(name = "settled_at")
    var settledAt: LocalDateTime? = null
        protected set

    val settled: Boolean get() = settledAt != null

    fun settle(at: LocalDateTime) {
        settledAt = at
    }
}
