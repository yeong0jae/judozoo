package at.backend.issue.application

import at.backend.issue.domain.DailyIssue
import at.backend.issue.infrastructure.repository.DailyIssueRepository
import at.backend.library.exception.EntityNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 거래일별 이슈 메모 CRUD. 타임라인(이슈 화면)이 조회·작성·수정·삭제에 쓴다. */
@Service
class DailyIssueService(
    private val repository: DailyIssueRepository,
) {

    @Transactional(readOnly = true)
    fun issuesOn(date: LocalDate): List<DailyIssue> =
        repository.findByTradeDateOrderByCreatedAtAsc(date)

    @Transactional
    fun add(date: LocalDate, content: String): DailyIssue {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "이슈 내용이 비어 있습니다" }
        return repository.save(DailyIssue(tradeDate = date, content = trimmed))
    }

    @Transactional
    fun edit(id: Long, content: String): DailyIssue {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "이슈 내용이 비어 있습니다" }
        val issue = repository.findById(id).orElseThrow { EntityNotFoundException("이슈 없음: $id") }
        issue.edit(trimmed)
        return issue
    }

    @Transactional
    fun delete(id: Long) {
        if (!repository.existsById(id)) throw EntityNotFoundException("이슈 없음: $id")
        repository.deleteById(id)
    }
}
