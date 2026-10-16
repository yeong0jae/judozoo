package judozoo.leadercalendar.domain

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
 * 한 시장의 한 거래일. [tradeDate]는 **현지 날짜**다 — US는 뉴욕 기준.
 *
 * 날과 종목([LeaderDayStock])을 나눈다 — 날 행만 있으면 "주도주 없음"([closed]면 휴장), 날 행이 없으면 "기록 없음"이다.
 * FK는 걸지 않는다(코드베이스 관례). 날을 지울 때 종목도 같은 트랜잭션에서 지운다.
 */
@Entity
@Table(name = "leader_day")
class LeaderDay(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KR','US')")
    val region: Region,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "taken_at") val takenAt: LocalDateTime,
    /** 휴장일 — 종목 행이 없다. 이름(추석 등)은 휴장 판정 API가 주지 않아 남기지 않는다 */
    val closed: Boolean,
    @Column(name = "created_at") val createdAt: LocalDateTime = takenAt,
    @Column(name = "updated_at") val updatedAt: LocalDateTime = takenAt,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
