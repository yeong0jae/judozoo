package judozoo.leadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 종목 시그널 전이 한 건. 전이 순간의 컨텍스트를 함께 박아 둬 재조회 없이 복기할 수 있게 한다.
 *
 * `event_type`은 **글자 컬럼**이다 — MySQL 네이티브 ENUM은 값을 추가할 때 INSERT가 잘려 터진다. 옛 종류의 행도 남아 있다.
 * `gap_rate`(돌파)·`ma`(종목 반등·꺾임)는 그 종류가 사라져 늘 비어 있다 — 컬럼은 이력 때문에 남긴다.
 * 소수 컬럼은 **단정밀도 `float`**라 [exact]로 MySQL 글자 표현과 같은 값을 낸다.
 */
@Entity
@Table(name = "signal_event")
class SignalEvent(
    @Column(name = "occurred_at") val occurredAt: LocalDateTime,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "stock_code") val stockCode: String,
    @Column(name = "stock_name") val stockName: String,
    @Column(name = "event_type") val eventType: String,
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
        /** 거래대금 스파이크 전이 한 건. */
        fun volumeSpike(at: LocalDateTime, day: LocalDate, stock: LeadingStockSnapshot, spike: MinuteCandles.VolumeSpike) = SignalEvent(
            at, day, stock.stockCode, stock.stockName, SignalEventType.VOLUME_SPIKE.name, stock.currentPrice, stock.priceChangeRate.toFloat(),
            stock.accumulatedTradingValue, spike.ratio.toFloat(), spike.latestTradingValue, spike.direction, at, at,
        )
    }
}
