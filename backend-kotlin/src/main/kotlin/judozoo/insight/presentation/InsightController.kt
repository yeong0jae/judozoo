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

/**
 * 왜 오르나 API — 지금 세션의 종목별 사유.
 *
 * 키워드·사유 문장·기준 시각은 공개다(한 줄은 가입 유도). 근거·관련 기사는 로그인 뒤라,
 * 방문자에게는 건수만 내려 "n건은 로그인 후 볼 수 있습니다"를 그리게 한다.
 * 주도주 응답에 끼워 넣지 않는다 — 주도주가 insight에 기대지 않고, 갱신 주기도 다르다(15초와 분).
 */
@RestController
@RequestMapping("/api/insight")
class InsightController(private val service: InsightService, private val clock: Clock) {

    /** [explained]가 false면 뉴스로 설명되지 않았다 — 목록에는 아무것도 붙이지 않고, 상세에는 관련 기사만. 기사는 로그인일 때만. */
    data class StockReasonItem(
        val code: String,
        val explained: Boolean,
        val keywords: List<String>,
        val reason: String,
        /** KST. 화면의 "기준" 시각 */
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
