package at.backend.leadingstock.presentation

import at.backend.leadingstock.application.SignalAnalysis
import at.backend.leadingstock.application.SignalAnalysisService
import at.backend.library.web.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 시그널 사후 분석 — 라벨링 트리거 + 날짜별 분석 데이터 제공. */
@RestController
class SignalAnalysisController(
    private val service: SignalAnalysisService,
) {

    /** 그 날짜 신호를 라벨링(멱등). 반환=라벨링한 신호 수. 테마 캘린더 '수집'과 동일한 수동 트리거. */
    @PostMapping("/api/signal-analysis/label")
    fun label(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): ApiResponse<Int> = ApiResponse.ok(service.labelDate(date))

    @GetMapping("/api/signal-analysis")
    fun analysis(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): ApiResponse<SignalAnalysis> = ApiResponse.ok(service.analysisOf(date))
}
