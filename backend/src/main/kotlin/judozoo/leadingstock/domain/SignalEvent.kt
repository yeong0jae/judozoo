package judozoo.leadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "signal_event")
class SignalEvent(
    @Column(name = "occurred_at") val occurredAt: LocalDateTime,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "stock_code") val stockCode: String,
    @Column(name = "stock_name") val stockName: String,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "event_type") val eventType: SignalEventType,
    @Column(name = "current_price") val currentPrice: Long,
    @Column(name = "price_change_rate") private val priceChangeRateFloat: Float,
    @Column(name = "trading_value") val tradingValue: Long,
    @Column(name = "spike_ratio") private val spikeRatioFloat: Float?,
    @Column(name = "minute_trading_value") val minuteTradingValue: Long?,
    @Enumerated(EnumType.STRING)
    @Column(name = "spike_direction", columnDefinition = "enum('BUY','SELL','FLAT')")
    val spikeDirection: SpikeDirection?,
    @Column(name = "created_at") val createdAt: LocalDateTime,
    @Column(name = "updated_at") val updatedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    @Column(name = "gap_rate")
    private val gapRateFloat: Float? = null

    @Column(name = "ma")
    val ma: Long? = null

    val priceChangeRate: Double get() = exact(priceChangeRateFloat)
    val gapRate: Double? get() = gapRateFloat?.let(::exact)
    val spikeRatio: Double? get() = spikeRatioFloat?.let(::exact)

    private fun exact(value: Float): Double = value.toString().toDouble()

    companion object {
        fun volumeSpike(at: LocalDateTime, day: LocalDate, stock: LeadingStockSnapshot, spike: MinuteCandles.VolumeSpike) = SignalEvent(
            at, day, stock.stockCode, stock.stockName, SignalEventType.VOLUME_SPIKE, stock.currentPrice, stock.priceChangeRate.toFloat(),
            stock.accumulatedTradingValue, spike.ratio.toFloat(), spike.latestTradingValue, spike.direction, at, at,
        )
    }
}
