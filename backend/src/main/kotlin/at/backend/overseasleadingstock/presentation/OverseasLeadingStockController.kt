package at.backend.overseasleadingstock.presentation

import at.backend.library.time.TimeProvider
import at.backend.library.web.ApiResponse
import at.backend.overseasleadingstock.application.OverseasIndexSnapshotService
import at.backend.overseasleadingstock.application.OverseasLeadingStockService
import at.backend.overseasleadingstock.presentation.response.OverseasDailyCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasIndexCloseSnapshotItem
import at.backend.overseasleadingstock.presentation.response.OverseasMinuteCandleItem
import at.backend.overseasleadingstock.presentation.response.OverseasStockDetailResponse
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/overseas-leading-stocks")
class OverseasLeadingStockController(
    private val service: OverseasLeadingStockService,
    private val indexSnapshotService: OverseasIndexSnapshotService,
    private val timeProvider: TimeProvider,
) {

    /**
     * 해외주식 거래대금순위 — 나스닥·뉴욕·아멕스 통합 상위 40위.
     * minChangeRate: 당일 등락률 임계값(%). 사용자가 -12~7 중 선택, 미지정 시 기본값.
     */
    @GetMapping("/ranking")
    fun getRanking(
        @RequestParam(required = false) minChangeRate: Int?,
    ): ApiResponse<List<OverseasStockRankItem>> {
        val rate = minChangeRate?.coerceIn(MIN_CHANGE_RATE, MAX_CHANGE_RATE)?.toDouble()
            ?: DEFAULT_MIN_CHANGE_RATE
        return ApiResponse.ok(service.getRanking(rate))
    }

    /**
     * 해외지수(나스닥종합) 장 마감 스냅샷 — 타임라인용. date 미지정 시 오늘.
     * 영업일(미국)이 키라 KST 날짜와 같은 달력 칸에 들어간다.
     */
    @GetMapping("/index-close-snapshots")
    fun getIndexCloseSnapshots(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
    ): ApiResponse<List<OverseasIndexCloseSnapshotItem>> {
        val day = date ?: timeProvider.today()
        val items = indexSnapshotService.snapshotsOn(day).map { s ->
            OverseasIndexCloseSnapshotItem(
                capturedAt = s.capturedAt,
                code = s.code,
                name = s.name,
                indexValue = s.indexValue,
                changeRate = s.changeRate,
            )
        }
        return ApiResponse.ok(items)
    }

    /** 종목 상세 — 필터(거래대금순위·등락률·시총) 평가. */
    @GetMapping("/{exchange}/{symbol}")
    fun getStockDetail(
        @PathVariable exchange: String,
        @PathVariable symbol: String,
    ): ApiResponse<OverseasStockDetailResponse> =
        ApiResponse.ok(service.evaluateStock(exchange.uppercase(), symbol.uppercase()))

    /** 종목 1분봉 — 상세 캔들차트용. */
    @GetMapping("/{exchange}/{symbol}/minute-candles")
    fun getMinuteCandles(
        @PathVariable exchange: String,
        @PathVariable symbol: String,
    ): ApiResponse<List<OverseasMinuteCandleItem>> =
        ApiResponse.ok(service.minuteCandles(exchange.uppercase(), symbol.uppercase()))

    /** 종목 일봉 — 일봉 차트용. */
    @GetMapping("/{exchange}/{symbol}/daily-candles")
    fun getDailyCandles(
        @PathVariable exchange: String,
        @PathVariable symbol: String,
    ): ApiResponse<List<OverseasDailyCandleItem>> =
        ApiResponse.ok(service.dailyCandles(exchange.uppercase(), symbol.uppercase()))

    companion object {
        private const val MIN_CHANGE_RATE = -12
        private const val MAX_CHANGE_RATE = 7
        private const val DEFAULT_MIN_CHANGE_RATE = 7.0
    }
}
