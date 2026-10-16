package judozoo.insight.presentation

import jakarta.validation.constraints.Pattern
import judozoo.auth.domain.CurrentUser
import judozoo.insight.application.InsightService
import judozoo.insight.domain.StockReason
import judozoo.library.KST
import judozoo.library.web.ApiResponse
import judozoo.market.domain.Region
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/insight")
class InsightController(private val service: InsightService, private val clock: Clock) {
    data class StockReasonItem(
        val code: String,
        val explained: Boolean,
        val keywords: List<String>,
        val reason: String,
        val generatedAt: LocalDateTime,
        val evidenceCount: Int,
        val relatedCount: Int,
        val evidence: List<StockReason.Article>?,
        val related: List<StockReason.Article>?,
    )

    @GetMapping("/reasons")
    fun reasons(@RequestParam @Pattern(regexp = "kr|us") market: String, user: CurrentUser?) = ApiResponse.ok(
        service.reasons(Region.valueOf(market.uppercase()), LocalDateTime.now(clock.withZone(KST))).map {
            val (evidence, related) = it.evidence to it.related
            StockReasonItem(
                it.code, it.explained, it.keywords, it.reason, it.generatedAt, evidence.size, related.size,
                evidence.takeIf { user != null }, related.takeIf { user != null },
            )
        },
    )
}
