package judozoo.leadertimeline.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.market.domain.Region
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 한 시장의 한 분. [at]은 **현지 시각**(분 단위)이다 — 해외는 뉴욕 시각.
 *
 * 분과 종목([LeaderTickStock])을 나눈다 — 분 행만 있으면 주도주가 없던 분, 분 행이 없으면 못 찍은 분이다.
 * FK는 걸지 않는다(코드베이스 관례). 분을 지울 때 종목도 같은 트랜잭션에서 지운다.
 */
@Entity
@Table(name = "leader_tick")
class LeaderTick(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KR','US')")
    val region: Region,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    val at: LocalDateTime,
    /** 실제로 찍은 시각(KST) — 화면의 "47초 전"이 이 값에서 나온다 */
    @Column(name = "created_at") val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
