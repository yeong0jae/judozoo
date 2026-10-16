package judozoo.stock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 거래 가능한 상장 종목. 검색 판정([matches])은 객체가 직접 답한다.
 *
 * 카탈로그 갱신은 항상 전체 교체(delete-all + insert-all)다(매일 07:40, [judozoo.stock.application.StockCatalog.refresh]).
 */
@Entity
@Table(name = "stocks")
class Stock(
    /** 종목을 가리킬 때는 이 단축코드를 쓴다. `id`는 갱신마다 새로 발급돼 바깥에 내보내지 않는다. */
    @Column(name = "short_code")
    val shortCode: String,
    @Column(name = "standard_code")
    val standardCode: String,
    val name: String,
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KOSPI','KOSDAQ')")
    val market: Market,
    @Column(name = "created_at")
    val createdAt: LocalDateTime,
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 질의가 종목명에 포함되거나 종목코드 접두사로 일치하면 true. 대소문자 무시. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isNotEmpty() && (q in name.lowercase() || shortCode.lowercase().startsWith(q))
    }

    /** 검색 관련도 — 0 종목명 시작, 1 코드 시작, 2 종목명 포함. [q]는 이미 소문자다. */
    internal fun relevance(q: String): Int = when {
        name.lowercase().startsWith(q) -> 0
        shortCode.lowercase().startsWith(q) -> 1
        else -> 2
    }
}
