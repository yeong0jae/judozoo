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
    @Column(name = "`trigger`") val trigger: Trigger,
    override val published: Boolean,
    override val explained: Boolean,
    @Column(columnDefinition = "json") val keywords: Keywords,
    val reason: String,
    @Column(columnDefinition = "json") val evidence: Articles,
    @Column(columnDefinition = "json") val related: Articles,
    val model: String,
    @Column(columnDefinition = "text") val error: String?,
    val failure: Failure?,
) : Publication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    fun attempt(localAt: LocalDateTime) = Attempt(localAt, published, explained, failure)

    fun finishedAt(at: LocalDateTime) = StockReason(
        region, tradingDay, code, name, at, trigger, published, explained, keywords, reason, evidence, related, model, error, failure,
    )

    companion object {
        fun made(region: Region, tradingDay: LocalDate, code: String, name: String, at: LocalDateTime, trigger: Trigger, verdict: Verdict, model: String) =
            StockReason(
                region, tradingDay, code, name, at, trigger, verdict.published, verdict.explained,
                Keywords(verdict.keywords), verdict.reason.take(200), Articles.of(verdict.evidence), Articles.of(verdict.related),
                model, verdict.error, verdict.failure,
            )
    }
}
