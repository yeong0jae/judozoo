package at.backend.stock.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 거래 가능한 상장 종목. 검색해 매매 명령을 낼 때 코드/이름을 조회하는 기준 데이터.
 *
 * 카탈로그 갱신은 항상 전체 교체(delete-all + insert-all)이므로, 모든 row의 [createdAt]은
 * 한 번의 갱신 시각으로 정렬된다 — "오늘 갱신되었는가"는 별도 동기화 테이블 없이
 * 가장 최근 [createdAt] 하나로 판정한다.
 */
@Entity
@Table(name = "stocks")
class Stock(

    @Id
    @Column(length = 12)
    val shortCode: String,

    @Column(nullable = false, length = 12)
    val standardCode: String,

    @Column(nullable = false, length = 50)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    val market: Market,

) : BaseEntity() {

    /** 질의가 종목명에 포함되거나 종목코드 접두사로 일치하면 true. 대소문자 무시. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return name.lowercase().contains(q) || shortCode.lowercase().startsWith(q)
    }
}
