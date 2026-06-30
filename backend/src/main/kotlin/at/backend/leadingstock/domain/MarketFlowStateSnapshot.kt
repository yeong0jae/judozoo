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
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

/**
 * 흐름 전환(InvestorFlowState) 상태 스냅샷 — 재시작으로 메모리가 비워져도 정점을 복원하기 위함.
 * (시장, 투자자)별 한 행을 매 폴 upsert한다. [tradeDate]가 다르면 새 날 = 새 추적으로 본다.
 */
@Entity
@Table(
    name = "market_flow_state",
    uniqueConstraints = [UniqueConstraint(name = "uk_market_flow_state", columnNames = ["market", "investor"])],
)
class MarketFlowStateSnapshot(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val market: Market,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val investor: InvestorType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var side: NetTradeSide,

    @Column(name = "extreme_eok", nullable = false)
    var extremeEok: Long,

    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
