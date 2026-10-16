package judozoo.insight.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import judozoo.market.domain.Region
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 왜 오르나 한 번 — **생성할 때마다 한 행**. 이력은 이 테이블 자체다.
 *
 * 화면은 (시장, 종목, 거래일)마다 [Shown]이 고른 한 행만 읽는다. 30분 규칙·재시도·하루 상한도
 * 행만 보고 판단한다 — 카운터나 상태 열을 따로 두지 않는다(026 §설계 결정).
 *
 * 키워드·기사는 JSON 열이다. 글자로 들고 있다가 읽을 때 푼다 — Python이 남긴 행과 모양이 같다.
 */
@Entity
@Table(name = "stock_reason")
class StockReason(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KR','US')")
    val region: Region,
    /** 현지 거래일 — US는 뉴욕 기준 */
    @Column(name = "trading_day") val tradingDay: LocalDate,
    /** KR 6자리 단축코드 / US 심볼 */
    val code: String,
    val name: String,
    /** KST. 화면의 "기준" 시각 */
    @Column(name = "generated_at") override val generatedAt: LocalDateTime,
    // MySQL 예약어라 따옴표로 감싼다
    @Column(name = "`trigger`") val trigger: String,
    /** 거절되면 false — 화면에 안 쓰인다. 다시 만들지는 [failure]가 정한다 */
    override val published: Boolean,
    override val explained: Boolean,
    @Column(name = "keywords", columnDefinition = "json") private val keywordsJson: String,
    val reason: String,
    @Column(name = "evidence", columnDefinition = "json") private val evidenceJson: String,
    @Column(name = "related", columnDefinition = "json") private val relatedJson: String,
    val model: String,
    @Column(columnDefinition = "text") val error: String?,
    /** 거절된 까닭([Failure.value]). 게시됐으면 NULL. 이 열이 생기기 전(2026-10-03) 거절 행도 NULL이다 */
    @Column(name = "failure") private val failureValue: String?,
) : Publication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 화면에 나가는 기사 한 건 — JSON 열의 원소 모양 그대로다. */
    data class Article(val source: String, val title: String, val url: String)

    val keywords: List<String> get() = JSON.readValue(keywordsJson)
    val evidence: List<Article> get() = JSON.readValue(evidenceJson)
    val related: List<Article> get() = JSON.readValue(relatedJson)
    val failure: Failure? get() = failureValue?.let(Failure::of)

    /** 30분·재시도 판단용. 시각은 부르는 쪽이 시장 현지 시각으로 바꿔 준다. */
    fun attempt(localAt: LocalDateTime) = Attempt(localAt, published, explained, failure)

    /** 만든 시각을 그 종목을 다 만든 때로 옮긴다 — 한 실행 안의 종목은 끝나는 때가 다르다. */
    fun finishedAt(at: LocalDateTime) = StockReason(
        region, tradingDay, code, name, at, trigger, published, explained, keywordsJson, reason, evidenceJson, relatedJson, model, error, failureValue,
    )

    companion object {
        private val JSON = JsonMapper.builder().build()

        fun made(region: Region, tradingDay: LocalDate, code: String, name: String, at: LocalDateTime, trigger: Trigger, verdict: Verdict, model: String) =
            StockReason(
                region, tradingDay, code, name, at, trigger.value, verdict.published, verdict.explained,
                JSON.writeValueAsString(verdict.keywords), verdict.reason.take(200),
                JSON.writeValueAsString(verdict.evidence.map(::article)), JSON.writeValueAsString(verdict.related.map(::article)),
                model, verdict.error, verdict.failure?.value,
            )

        private fun article(s: Source) = Article(s.domain, s.title, s.url)
    }
}
