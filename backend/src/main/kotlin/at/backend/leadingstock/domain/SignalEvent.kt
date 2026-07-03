package at.backend.leadingstock.domain

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
 * 시그널 전이 한 건 — 전이가 일어난 순간의 컨텍스트를 함께 박아 둬, 재조회 없이 복기/리플레이가 가능하게 한다.
 * [gapRate]는 돌파 계열에만, [spikeRatio]·[minuteTradingValue]·[spikeDirection]은 스파이크에만,
 * [ma20]은 돌림에만 채워진다.
 */
@Entity
@Table(
    name = "signal_event",
    indexes = [
        Index(name = "idx_signal_event_date", columnList = "trade_date"),
    ],
)
class SignalEvent(

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: LocalDateTime,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(nullable = false)
    val stockCode: String,

    @Column(nullable = false)
    val stockName: String,

    // varchar로 고정 — MySQL 네이티브 ENUM으로 만들면 enum 값 추가 시 ddl-auto가 컬럼을 안 고쳐 insert가 truncate로 터진다.
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, columnDefinition = "varchar(32)")
    val eventType: SignalEventType,

    @Column(nullable = false)
    val currentPrice: Long,

    @Column(nullable = false)
    val priceChangeRate: Double,

    @Column(nullable = false)
    val tradingValue: Long,

    @Column
    val gapRate: Double? = null,

    @Column
    val spikeRatio: Double? = null,

    @Column(name = "minute_trading_value")
    val minuteTradingValue: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "spike_direction")
    val spikeDirection: SpikeDirection? = null,

    @Column
    val ma20: Long? = null,

    @Column
    val theme: String? = null,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
