package at.backend.leadingstock.domain

import at.backend.library.jpa.BaseEntity
import at.backend.stock.domain.Market
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 영속화된 지수 1분봉 한 행. (시장, 분)으로 유일 — 폴러가 진행 중인 분을 더 완성된 값으로 upsert한다.
 * 합성 값 객체 [IndexMinuteCandle]과 분리: 이쪽은 시장·일자·식별자를 갖는 저장 레코드.
 */
@Entity
@Table(
    name = "index_minute_candle",
    uniqueConstraints = [UniqueConstraint(name = "uk_index_minute_candle", columnNames = ["market", "minute_at"])],
    indexes = [Index(name = "idx_index_minute_candle_date", columnList = "market, trade_date")],
)
class IndexMinuteCandleEntity(

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false)
    val market: Market,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(name = "minute_at", nullable = false)
    val minute: LocalDateTime,

    @Column(name = "open_price", nullable = false)
    var open: Double,

    @Column(name = "high_price", nullable = false)
    var high: Double,

    @Column(name = "low_price", nullable = false)
    var low: Double,

    @Column(name = "close_price", nullable = false)
    var close: Double,

    @Column(name = "volume", nullable = false)
    var volume: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    /** 같은 분의 더 완성된 합성 값으로 OHLCV 갱신. */
    fun updateFrom(c: IndexMinuteCandle) {
        open = c.open
        high = c.high
        low = c.low
        close = c.close
        volume = c.volume
    }

    fun toDomain() = IndexMinuteCandle(minute, open, high, low, close, volume)
}
