package at.backend.report.presentation

import at.backend.library.web.ApiResponse
import at.backend.library.time.TimeProvider
import at.backend.report.application.ReportService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class ReportController(
    private val reportService: ReportService,
    private val timeProvider: TimeProvider,
) {

    @GetMapping("/api/reports/daily")
    fun daily(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
    ) = ApiResponse.ok(reportService.findDaily(date ?: timeProvider.today()))
}
