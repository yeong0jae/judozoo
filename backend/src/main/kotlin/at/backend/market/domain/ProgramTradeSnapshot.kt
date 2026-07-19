package at.backend.market.domain

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
 * 시장(코스피/코스닥) 프로그램 매매 당일 누적 스냅샷 — 폴러가 매 폴마다 한 줄씩 적재한다.
 * ka90010이 주는 값이 '당일 누적'이라, 세션(오전/오후/막판) 순매수는 구간 경계 스냅샷끼리 빼서 구한다.
 * 단위는 백만원(부호 포함, 양수 = 순매수).
 */
@Entity
@Table(
    name = "program_trade_snapshot",
    indexes = [
        Index(name = "idx_program_trade_snapshot_lookup", columnList = "market, trade_date, captured_at"),
    ],
)
class ProgramTradeSnapshot(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    val market: Market,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(name = "captured_at", nullable = false)
    val capturedAt: LocalDateTime,

    @Column(name = "arbitrage_mil", nullable = false)
    val arbitrageMil: Long,      // 차익

    @Column(name = "non_arbitrage_mil", nullable = false)
    val nonArbitrageMil: Long,   // 비차익

    @Column(name = "total_mil", nullable = false)
    val totalMil: Long,          // 전체
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
