package judozoo.leadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "stock_minute_candle")
class StockMinuteCandleEntity(
    @Column(name = "stock_code")
    val stockCode: String,
    @Column(name = "trade_date")
    val tradeDate: LocalDate,
    @Column(name = "minute_at")
    val minuteAt: LocalDateTime,
    @Column(name = "open_price")
    val openPrice: Long,
    @Column(name = "high_price")
    val highPrice: Long,
    @Column(name = "low_price")
    val lowPrice: Long,
    @Column(name = "close_price")
    val closePrice: Long,
    val volume: Long,
    @Column(name = "trading_value")
    val tradingValue: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    fun toCandle() = MinuteCandle(minuteAt, openPrice, highPrice, lowPrice, closePrice, volume, tradingValue)

    companion object {
        fun of(stockCode: String, day: LocalDate, c: MinuteCandle) =
            StockMinuteCandleEntity(stockCode, day, c.dateTime, c.openPrice, c.highPrice, c.lowPrice, c.closePrice, c.volume, c.tradingValue)
    }
}
