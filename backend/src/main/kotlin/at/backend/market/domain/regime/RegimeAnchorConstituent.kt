package at.backend.market.domain.regime

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 08:15에 고정한 오전 NXT 바스켓 구성원 한 종목 — 영속본.
 *
 * 앵커는 인메모리로만 들면 장중 재시작 시 사라져 Gap2를 복구할 수 없다. 그래서 캡처 시
 * DB에 저장해두고, 재시작 후 [MorningBasket]을 복원한다(당일 1행/종목).
 */
@Entity
@Table(name = "regime_anchor", indexes = [Index(name = "idx_regime_anchor_date", columnList = "date")])
class RegimeAnchorConstituent(

    @Column(nullable = false)
    val date: LocalDate,

    @Column(nullable = false)
    val stockCode: String,

    @Column(nullable = false)
    val rateAt0815: Double,

    @Column(nullable = false)
    val weight: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
