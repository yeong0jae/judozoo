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
 * 흐름 전환 상태 스냅샷 — 재시작으로 메모리가 비워져도 정점을 복원하기 위함.
 * (시장, 투자자)별 한 행을 매 폴 갱신한다. `trade_date`가 다르면 새 날 = 새 추적.
 */
@Entity
@Table(name = "market_flow_state")
class MarketFlowState(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KOSPI','KOSDAQ')")
    val market: Market,
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('FOREIGN','INSTITUTION','INDIVIDUAL')")
    val investor: InvestorType,
    side: NetTradeSide,
    extremeEok: Long,
    tradeDate: LocalDate,
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('BUY','SELL')")
    var side: NetTradeSide = side
        protected set

    @Column(name = "extreme_eok")
    var extremeEok: Long = extremeEok
        protected set

    @Column(name = "trade_date")
    var tradeDate: LocalDate = tradeDate
        protected set

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = createdAt
        protected set

    /** 그날의 추적 상태로 갈아 끼운다. */
    fun track(state: InvestorFlowState, day: LocalDate, at: LocalDateTime) {
        side = requireNotNull(state.side)
        extremeEok = state.extremeEok
        tradeDate = day
        updatedAt = at
    }

    fun state() = InvestorFlowState(side, extremeEok)
}
