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

/**
 * 영속화된 지수 1분봉. (시장, 분)으로 유일 — 진행 중인 분을 더 완성된 값으로 갱신한다.
 * 가격은 **단정밀도 `float`**라 읽을 때 MySQL 글자 표현과 같은 값으로 넓힌다.
 */
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

    /** 진행 중이던 분을 더 완성된 값으로 갈아 끼운다. */
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
