package at.backend.overseasleadingstock.presentation

import at.backend.library.web.ApiResponse
import at.backend.overseasleadingstock.application.OverseasLeadingStockService
import at.backend.overseasleadingstock.presentation.response.OverseasStockRankItem
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/overseas-leading-stocks")
class OverseasLeadingStockController(
    private val service: OverseasLeadingStockService,
) {

    /** 해외주식 거래대금순위 — excd: NYS, NAS, AMS */
    @GetMapping("/ranking/{excd}")
    fun getRanking(@PathVariable excd: String): ApiResponse<List<OverseasStockRankItem>> {
        require(excd in SUPPORTED_EXCHANGES) { "지원하지 않는 거래소: $excd" }
        return ApiResponse.ok(service.getRanking(excd))
    }

    companion object {
        private val SUPPORTED_EXCHANGES = setOf("NYS", "NAS", "AMS")
    }
}
