package at.backend.issue.presentation

import at.backend.issue.application.DailyIssueService
import at.backend.issue.domain.DailyIssue
import at.backend.library.web.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 거래일별 이슈 메모 API — 타임라인(이슈) 화면의 조회·작성·수정·삭제. */
@RestController
@RequestMapping("/api/issues")
class DailyIssueController(
    private val service: DailyIssueService,
) {

    @GetMapping
    fun list(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = ApiResponse.ok(service.issuesOn(date).map { it.toItem() })

    @PostMapping
    fun create(@RequestBody req: IssueCreateRequest) =
        ApiResponse.created(service.add(req.date, req.content).toItem())

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody req: IssueUpdateRequest) =
        ApiResponse.ok(service.edit(id, req.content).toItem())

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Nothing> {
        service.delete(id)
        return ApiResponse.accepted()
    }
}

data class IssueCreateRequest(
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val date: LocalDate,
    val content: String,
)

data class IssueUpdateRequest(val content: String)

data class DailyIssueItem(
    val id: Long,
    val date: LocalDate,
    val content: String,
)

private fun DailyIssue.toItem() = DailyIssueItem(id = id, date = tradeDate, content = content)
