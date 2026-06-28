package at.backend.overseasleadingstock.presentation

import at.backend.library.web.ApiResponse
import at.backend.overseasleadingstock.application.OverseasLeadingStockService
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/overseas-leading-stocks")
class OverseasLeadingStockController(
    private val service: OverseasLeadingStockService,
) {

    /** 해외주식 거래대금순위 — 나스닥·뉴욕·아멕스 통합 상위 40위 */
    @GetMapping("/ranking")
    fun getRanking(): ApiResponse<List<OverseasStockRankItem>> =
        ApiResponse.ok(service.getRanking())
}
