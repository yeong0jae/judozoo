package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/** 베팅·변경·취소 한 건 — LIVE 목록이다. 판돈·참여 수의 원천이 아니다. 취소는 [amountMan]이 음수. */
@Entity
@Table(name = "closing_bet_event")
class ClosingBetEvent(
    @Column(name = "trading_day") val tradingDay: LocalDate,
    @Column(name = "user_id") val userId: Long,
    @Column(name = "stock_name") val stockName: String,
    @Column(name = "amount_man") val amountMan: Int,
    val at: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
