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

@Entity
@Table(name = "stock_reason")
class StockReason(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KR','US')")
    val region: Region,
    @Column(name = "trading_day") val tradingDay: LocalDate,
    val code: String,
    val name: String,
    @Column(name = "generated_at") override val generatedAt: LocalDateTime,
    @Column(name = "`trigger`") val trigger: String,
    override val published: Boolean,
    override val explained: Boolean,
    @Column(name = "keywords", columnDefinition = "json") private val keywordsJson: String,
    val reason: String,
    @Column(name = "evidence", columnDefinition = "json") private val evidenceJson: String,
    @Column(name = "related", columnDefinition = "json") private val relatedJson: String,
    val model: String,
    @Column(columnDefinition = "text") val error: String?,
    @Column(name = "failure") private val failureValue: String?,
) : Publication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    data class Article(val source: String, val title: String, val url: String)

    val keywords: List<String> get() = JSON.readValue(keywordsJson)
    val evidence: List<Article> get() = JSON.readValue(evidenceJson)
    val related: List<Article> get() = JSON.readValue(relatedJson)
    val failure: Failure? get() = failureValue?.let(Failure::of)

    fun attempt(localAt: LocalDateTime) = Attempt(localAt, published, explained, failure)

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
