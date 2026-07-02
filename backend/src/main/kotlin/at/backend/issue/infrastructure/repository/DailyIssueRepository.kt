package at.backend.issue.infrastructure.repository

import at.backend.issue.domain.DailyIssue
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyIssueRepository : JpaRepository<DailyIssue, Long> {

    /** 그 거래일의 이슈를 입력 순(오래된→최신)으로. */
    fun findByTradeDateOrderByCreatedAtAsc(tradeDate: LocalDate): List<DailyIssue>
}
