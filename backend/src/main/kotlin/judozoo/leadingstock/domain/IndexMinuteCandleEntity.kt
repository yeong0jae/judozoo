package judozoo.leadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.stock.domain.Market
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "index_minute_candle")
class IndexMinuteCandleEntity(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KOSPI','KOSDAQ')")
    val market: Market,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "minute_at") val minuteAt: LocalDateTime,
    candle: IndexMinuteCandle,
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    @Column(name = "open_price")
    private var open: Float = candle.open.toFloat()

    @Column(name = "high_price")
    private var high: Float = candle.high.toFloat()

    @Column(name = "low_price")
    private var low: Float = candle.low.toFloat()

    @Column(name = "close_price")
    private var close: Float = candle.close.toFloat()

    private var volume: Long = candle.volume

    @Column(name = "updated_at")
    private var updatedAt: LocalDateTime = createdAt

    fun refresh(candle: IndexMinuteCandle, at: LocalDateTime) {
        open = candle.open.toFloat()
        high = candle.high.toFloat()
        low = candle.low.toFloat()
        close = candle.close.toFloat()
        volume = candle.volume
        updatedAt = at
    }

    fun toCandle() = IndexMinuteCandle(minuteAt, exact(open), exact(high), exact(low), exact(close), volume)

    private fun exact(value: Float): Double = value.toString().toDouble()
}
