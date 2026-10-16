package judozoo.leadercalendar.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.leadingstock.domain.LeadingStockSnapshot
import judozoo.overseasleadingstock.domain.OverseasStockRank
import java.math.BigDecimal
import java.math.RoundingMode

@Entity
@Table(name = "leader_day_stock")
class LeaderDayStock(
    @Column(name = "leader_day_id") val leaderDayId: Long,
    @Column(name = "`rank`", columnDefinition = "smallint") val rank: Int,
    val exchange: String?,
    val code: String,
    val name: String,
    val price: BigDecimal,
    @Column(name = "change_rate") private val changeRateFloat: Float,
    @Column(name = "trading_value") val tradingValue: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val changeRate: Double get() = changeRateFloat.toString().toDouble()

    companion object {
        fun domestic(dayId: Long, rank: Int, s: LeadingStockSnapshot) = LeaderDayStock(
            dayId, rank, null, s.stockCode.substringBefore("_"), s.stockName, BigDecimal.valueOf(s.currentPrice),
            s.priceChangeRate.toFloat(), BigDecimal.valueOf(s.accumulatedTradingValue),
        )

        fun overseas(dayId: Long, rank: Int, s: OverseasStockRank) = LeaderDayStock(
            dayId, rank, s.exchange, s.symbol, s.name, BigDecimal.valueOf(s.price), s.rate.toFloat(),
            BigDecimal.valueOf(s.tradingValue).setScale(2, RoundingMode.HALF_EVEN),
        )
    }
}
