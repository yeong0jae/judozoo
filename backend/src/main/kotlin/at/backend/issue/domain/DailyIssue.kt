package at.backend.issue.domain

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
 * 거래일에 사용자가 직접 남기는 이슈 메모 한 건. 한 날짜에 여러 개를 붙일 수 있다.
 * 이 앱의 유일한 사용자 쓰기 데이터 — 시장 데이터(전시용)와 달리 사람이 입력한다.
 */
@Entity
@Table(
    name = "daily_issue",
    indexes = [
        Index(name = "idx_daily_issue_date", columnList = "trade_date"),
    ],
)
class DailyIssue(

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(nullable = false, length = 500)
    var content: String,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    /** 내용 교체 — 공백만 남는 입력은 호출 전에 걸러진다. */
    fun edit(content: String) {
        this.content = content
    }
}
