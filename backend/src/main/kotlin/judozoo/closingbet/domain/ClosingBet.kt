package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 사람·판마다 한 행. 취소하면 지운다(LIVE 이력은 [ClosingBetEvent]에 남는다).
 * [status]는 open(베팅 중) → filled(20:00 체결) → settled(아침 매도). 한 주도 못 사게 오르면 void.
 */
@Entity
@Table(name = "closing_bet")
class ClosingBet(
    @Column(name = "user_id") val userId: Long,
    /** 판 — 베팅한 거래일 */
    @Column(name = "trading_day") val tradingDay: LocalDate,
    stockCode: String,
    stockName: String,
    amountMan: Int,
    placedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "stock_code")
    var stockCode: String = stockCode
        protected set

    @Column(name = "stock_name")
    var stockName: String = stockName
        protected set

    @Column(name = "amount_man")
    var amountMan: Int = amountMan
        protected set

    /** 지금 종목·금액으로 정한 시각 — 동점일 때 줄 순서 */
    @Column(name = "placed_at")
    var placedAt: LocalDateTime = placedAt
        protected set

    var status: String = OPEN
        protected set

    @Column(name = "buy_price", columnDefinition = "int")
    var buyPrice: Long? = null
        protected set

    var shares: Int? = null
        protected set

    /** (고가 + 저가) ÷ 2 — 0.5원 단위가 나온다 */
    @Column(name = "sell_price", columnDefinition = "decimal(12,1)")
    var sellPrice: Double? = null
        protected set

    var pnl: Long? = null
        protected set

    @Column(name = "rate")
    private var rateFloat: Float? = null

    val rate: Double? get() = rateFloat?.toString()?.toDouble()

    fun choose(code: String, name: String, amount: Int, at: LocalDateTime) {
        stockCode = code
        stockName = name
        amountMan = amount
        placedAt = at
        status = OPEN
    }

    /** 20:00 종가로 산다. 걸 때보다 올라 한 주도 못 사면 판에서 뺀다. */
    fun fill(price: Long) {
        buyPrice = price
        try {
            shares = BetRules.sharesFor(amountMan, price)
            status = FILLED
        } catch (e: BetRejected) {
            shares = 0
            status = VOID
        }
    }

    fun settle(sell: Double) {
        val outcome = Outcome.of(shares ?: 0, buyPrice ?: 0, sell)
        sellPrice = sell
        pnl = outcome.pnl
        rateFloat = outcome.rate.toFloat()
        status = SETTLED
    }

    fun result(): Result = Result(userId, stockCode, placedAt, Outcome(pnl ?: 0, rate ?: 0.0))

    fun stake(): Stake = Stake(userId, stockCode, amountMan, placedAt)

    companion object {
        const val OPEN = "open"
        const val FILLED = "filled"
        const val SETTLED = "settled"
        const val VOID = "void"
    }
}
