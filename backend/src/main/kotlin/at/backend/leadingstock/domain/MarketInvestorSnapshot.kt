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
 * 한 시장(코스피/코스닥)의 장중 투자자 순매수 스냅샷 — 폴러가 매 폴(2분)마다 한 줄씩 적재한다.
 * 시그널 선택 시 그 발생 시각 이하 가장 가까운 스냅샷을 찾아 '그때의 순매수'를 보여주는 용도. 단위는 억원(부호 포함).
 */
@Entity
@Table(
    name = "market_investor_snapshot",
    indexes = [
        Index(name = "idx_market_investor_snapshot_lookup", columnList = "market, trade_date, captured_at"),
    ],
)
class MarketInvestorSnapshot(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val market: Market,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(name = "captured_at", nullable = false)
    val capturedAt: LocalDateTime,

    @Column(name = "foreign_eok", nullable = false)
    val foreignEok: Long,

    @Column(name = "institution_eok", nullable = false)
    val institutionEok: Long,

    @Column(name = "individual_eok", nullable = false)
    val individualEok: Long,

    @Column(name = "index_value", nullable = false)
    val indexValue: Double,

    @Column(name = "change_rate", nullable = false)
    val changeRate: Double,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
