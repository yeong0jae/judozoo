package at.backend.market.presentation

import at.backend.library.web.ApiResponse
import at.backend.market.application.KospiIndexService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** KOSPI·KOSDAQ 종합지수 현재값 — 상단 스트립·지수 상세용. 키움 ka20001. */
@RestController
class MarketIndexController(
    private val kospiIndexService: KospiIndexService,
) {

    @GetMapping("/api/market/kospi")
    fun getKospi() = ApiResponse.ok(kospiIndexService.getKospi())

    @GetMapping("/api/market/kosdaq")
    fun getKosdaq() = ApiResponse.ok(kospiIndexService.getKosdaq())
}
