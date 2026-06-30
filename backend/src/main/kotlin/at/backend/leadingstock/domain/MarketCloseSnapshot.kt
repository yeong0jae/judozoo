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
import java.time.LocalDateTime

/**
 * 한 시장(코스피/코스닥)의 장 마감(15:40) 투자자 순매수 스냅샷 — 타임라인에 하루 한 줄로 찍힌다.
 * (시장, 거래일)별 한 행만 존재한다(같은 날 재캡처는 스킵). 순매수 단위는 억원(부호 포함).
 */
@Entity
@Table(
    name = "market_close_snapshot",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_market_close_snapshot", columnNames = ["market", "trade_date"]),
    ],
)
class MarketCloseSnapshot(

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

    @Column(name = "index_value")
    val indexValue: Double?,

    @Column(name = "change_rate")
    val changeRate: Double?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
