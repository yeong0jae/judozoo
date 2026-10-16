package judozoo.closingbet.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.stock.domain.Market
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "bet_round_stock")
class BetRoundStock(
    @Column(name = "trading_day") val tradingDay: LocalDate,
    @Column(name = "stock_code") val stockCode: String,
    @Column(name = "stock_name") val stockName: String,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) val market: Market,
    @Column(name = "`lead`") val lead: Boolean,
    val nxt: Boolean,
    @Column(name = "close_price", columnDefinition = "int") val closePrice: Long,
    @Column(name = "high_price", columnDefinition = "int") val highPrice: Long,
    @Column(name = "low_price", columnDefinition = "int") val lowPrice: Long,
    @Column(name = "change_rate") private val changeRateFloat: Float,
    @Column(name = "foreign_net") val foreignNet: Long,
    @Column(name = "institution_net") val institutionNet: Long,
    @Column(name = "foreign_5d") val foreign5d: Long,
    @Column(name = "institution_5d") val institution5d: Long,
    @Column(name = "recent_high_gap") private val recentHighGapFloat: Float?,
    @Column(name = "regular_close", columnDefinition = "int") val regularClose: Long?,
    @Column(columnDefinition = "json") val checks: Checks,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) val grade: Grade,
    val crowd: Int,
    @Column(name = "pot_man") val potMan: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "sell_price", columnDefinition = "decimal(12,1)")
    var sellPrice: Double? = null
        protected set

    @Column(name = "sell_window_start")
    var sellWindowStart: LocalDateTime? = null
        protected set

    @Column(name = "rate")
    private var rateFloat: Float? = null

    val changeRate: Double get() = exact(changeRateFloat)
    val recentHighGap: Double? get() = recentHighGapFloat?.let(::exact)
    val rate: Double? get() = rateFloat?.let(::exact)
    val sold: Boolean get() = rateFloat != null

    fun sell(price: SellWindow.SellPrice) {
        sellPrice = price.price
        sellWindowStart = price.windowStart
        rateFloat = Outcome.of(1, closePrice, price.price).rate.toFloat()
    }

    private fun exact(value: Float): Double = value.toString().toDouble()
}
