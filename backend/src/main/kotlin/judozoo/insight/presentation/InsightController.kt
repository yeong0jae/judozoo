package judozoo.insight.presentation

import jakarta.validation.constraints.Pattern
import judozoo.auth.domain.CurrentUser
import judozoo.insight.application.InsightService
import judozoo.insight.domain.Article
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
        val evidence: List<Article>?,
        val related: List<Article>?,
    )

    @GetMapping("/reasons")
    fun reasons(@RequestParam @Pattern(regexp = "kr|us") market: String, user: CurrentUser?) = ApiResponse.ok(
        service.reasons(Region.valueOf(market.uppercase()), LocalDateTime.now(clock.withZone(KST))).map {
            StockReasonItem(
                it.code, it.explained, it.keywords.values, it.reason, it.generatedAt, it.evidence.size, it.related.size,
                it.evidence.items.takeIf { user != null }, it.related.items.takeIf { user != null },
            )
        },
    )
}
