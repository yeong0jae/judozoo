package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 20:00에 찍어 두는 판. 복기는 "체결 시점 그대로"여야 해서 다음 날 다시 계산하지 않는다.
 * [marketJson]은 코스피·코스닥 × 외인·기관 × [현물 당일, 현물 5일, 선물 당일, 선물 5일, 마감, 애프터] + 야간선물 — 화면에 그대로 나간다.
 */
@Entity
@Table(name = "bet_round")
class BetRound(
    @Id @Column(name = "trading_day") val tradingDay: LocalDate,
    @Column(name = "snapped_at") val snappedAt: LocalDateTime,
    @Column(name = "market", columnDefinition = "json") val marketJson: String,
    val players: Int,
    @Column(name = "pot_man") val potMan: Long,
) {
    /** 랭킹 확정 시각. 늦게 체결된 종목이 있으면 09:05 뒤 */
    @Column(name = "settled_at")
    var settledAt: LocalDateTime? = null
        protected set

    val settled: Boolean get() = settledAt != null

    fun settle(at: LocalDateTime) {
        settledAt = at
    }
}
