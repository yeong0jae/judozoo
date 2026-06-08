package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.MarketRegimeService
import at.backend.market.application.RegimeBackfillService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시장 레짐 초기 스냅샷 제공. 이후 갱신은 STOMP `/topic/regime`.
 * 폴러가 아직 한 번도 산출하지 않았으면 data=null (집계 전/시간 밖).
 */
@RestController
class RegimeController(
    private val service: MarketRegimeService,
    private val backfillService: RegimeBackfillService,
) {

    @GetMapping("/api/market/regime")
    fun regime() = ApiResponse.ok(service.latest())

    /** 최근 20일 결과 — 멀티데이 비교 차트용. */
    @GetMapping("/api/market/regime/daily")
    fun daily() = ApiResponse.ok(service.recentDaily())

    /** 과거 N일치 regime_daily 초기화 (DB 비어 있을 때 1회 호출). */
    @PostMapping("/api/admin/regime/backfill")
    fun backfill(
        @RequestParam(defaultValue = "000660") stockCode: String,
        @RequestParam(defaultValue = "20") days: Int,
    ): ApiResponse<String> {
        backfillService.backfill(stockCode, days)
        return ApiResponse.ok("backfill completed: $days days")
    }
}
