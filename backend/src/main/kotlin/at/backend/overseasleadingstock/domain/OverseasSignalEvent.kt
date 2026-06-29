package at.backend.overseasleadingstock.domain

import at.backend.leadingstock.domain.SignalEventType
import at.backend.leadingstock.domain.SpikeDirection
import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 해외 시그널 전이 한 건. 국내 SignalEvent와 같은 구조 — 가격은 달러(Double), 거래소(exchange) 추가, 테마 제외.
 * [gapRate]는 돌파 계열에만, [spikeRatio]·[minuteTradingValue]·[spikeDirection]은 스파이크에만 채워진다.
 */
@Entity
@Table(
    name = "overseas_signal_event",
    indexes = [
        Index(name = "idx_overseas_signal_event_date", columnList = "trade_date"),
    ],
)
class OverseasSignalEvent(

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: LocalDateTime,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(nullable = false)
    val exchange: String,

    @Column(nullable = false)
    val symbol: String,

    @Column(nullable = false)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    val eventType: SignalEventType,

    @Column(nullable = false)
    val price: Double,

    @Column(nullable = false)
    val rate: Double,

    @Column(name = "trading_value", nullable = false)
    val tradingValue: Double,

    @Column(name = "gap_rate")
    val gapRate: Double? = null,

    @Column(name = "spike_ratio")
    val spikeRatio: Double? = null,

    @Column(name = "minute_trading_value")
    val minuteTradingValue: Double? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "spike_direction")
    val spikeDirection: SpikeDirection? = null,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
