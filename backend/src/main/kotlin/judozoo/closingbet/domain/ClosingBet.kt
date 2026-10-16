package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "closing_bet")
class ClosingBet(
    @Column(name = "user_id") val userId: Long,
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

    @Column(name = "placed_at")
    var placedAt: LocalDateTime = placedAt
        protected set

    var status: BetStatus = BetStatus.OPEN
        protected set

    @Column(name = "buy_price", columnDefinition = "int")
    var buyPrice: Long? = null
        protected set

    var shares: Int? = null
        protected set

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
        status = BetStatus.OPEN
    }

    fun fill(price: Long) {
        buyPrice = price
        val bought = BetRules.sharesOrNull(amountMan, price)
        shares = bought ?: 0
        status = if (bought == null) BetStatus.VOID else BetStatus.FILLED
    }

    fun settle(sell: Double) {
        val outcome = Outcome.of(shares ?: 0, buyPrice ?: 0, sell)
        sellPrice = sell
        pnl = outcome.pnl
        rateFloat = outcome.rate.toFloat()
        status = BetStatus.SETTLED
    }

    fun result(): Result = Result(userId, stockCode, placedAt, Outcome(pnl ?: 0, rate ?: 0.0))

    fun stake(): Stake = Stake(userId, stockCode, amountMan, placedAt)
}
