package at.backend.market.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 코스피 선물 시장의 장중 투자자 순매수 스냅샷 — 폴러가 매 폴마다 한 줄씩 적재한다.
 * KIS가 주는 값이 '당일 누적'이라, 세션(오전/오후/막판) 순매수는 구간 경계 스냅샷끼리 빼서 구한다.
 * 단위는 계약(부호 포함, 양수 = 순매수).
 */
@Entity
@Table(
    name = "futures_investor_snapshot",
    indexes = [
        Index(name = "idx_futures_investor_snapshot_lookup", columnList = "trade_date, captured_at"),
    ],
)
class FuturesInvestorSnapshot(

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(name = "captured_at", nullable = false)
    val capturedAt: LocalDateTime,

    @Column(name = "foreign_qty", nullable = false)
    val foreignQty: Long,

    @Column(name = "institution_qty", nullable = false)
    val institutionQty: Long,

    @Column(name = "individual_qty", nullable = false)
    val individualQty: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
