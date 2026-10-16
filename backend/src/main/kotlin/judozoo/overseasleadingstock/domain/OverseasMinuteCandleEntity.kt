package judozoo.overseasleadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "overseas_minute_candle")
class OverseasMinuteCandleEntity(
    val exchange: String,
    val symbol: String,
    @Column(name = "trading_day")
    val tradingDay: LocalDate,
    @Column(name = "minute_at")
    val minuteAt: LocalDateTime,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Long,
    @Column(name = "trading_value")
    val tradingValue: Float,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    fun toCandle() = OverseasMinuteCandle(minuteAt, tradingDay, exact(open), exact(high), exact(low), exact(close), volume, exact(tradingValue))

    private fun exact(value: Float): Double = value.toString().toDouble()

    companion object {
        fun of(exchange: String, symbol: String, day: LocalDate, c: OverseasMinuteCandle) = OverseasMinuteCandleEntity(
            exchange, symbol, day, c.dateTime, c.open.toFloat(), c.high.toFloat(), c.low.toFloat(), c.close.toFloat(), c.volume, c.tradingValue.toFloat(),
        )
    }
}
