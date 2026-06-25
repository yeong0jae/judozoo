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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 시장(코스피/코스닥) 단위 투자자 순매수 단계 전이 한 건.
 * 외국인/기관/개인의 당일 누적 순매수가 1조(코스피)·1,000억(코스닥) 단계를 넘나든 순간을 박아 둔다.
 * [netAmountEok]은 전이 시점의 누적 순매수(억원, 부호 포함), [level]은 도달 단계(1=1단계).
 */
@Entity
@Table(
    name = "market_signal_event",
    indexes = [
        Index(name = "idx_market_signal_event_date", columnList = "trade_date"),
    ],
)
class MarketSignalEvent(

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: LocalDateTime,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false)
    val market: Market,

    @Enumerated(EnumType.STRING)
    @Column(name = "investor", nullable = false)
    val investor: InvestorType,

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    val side: NetTradeSide,

    @Column(name = "step_level", nullable = false)
    val level: Int,

    @Column(name = "net_amount_eok", nullable = false)
    val netAmountEok: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
