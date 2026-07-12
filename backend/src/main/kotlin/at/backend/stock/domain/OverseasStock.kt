package at.backend.stock.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 해외 상장 종목(미국). 검색해 관심 테마에 담을 때 쓰는 기준 데이터.
 * 국내 [Stock]과 테이블을 나눈 이유: 식별자가 종목코드가 아니라 (거래소, 심볼) 쌍이고, 시세 조회 경로도 다르다.
 */
@Entity
@Table(
    name = "overseas_stocks",
    indexes = [Index(name = "idx_overseas_stocks_symbol", columnList = "exchange, symbol")],
)
class OverseasStock(

    @Column(nullable = false, length = 3)
    val exchange: String, // NAS / NYS / AMS

    @Column(nullable = false, length = 16)
    val symbol: String, // AAPL

    @Column(nullable = false, length = 100)
    val name: String, // 애플 (한글명)

    @Column(name = "english_name", nullable = false, length = 120)
    val englishName: String, // APPLE INC
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    /** 종목명(한글·영문)이나 심볼에 [query]가 들어가면 매칭. 심볼은 대소문자를 가리지 않는다. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        return name.contains(q, ignoreCase = true) ||
            englishName.contains(q, ignoreCase = true) ||
            symbol.contains(q, ignoreCase = true)
    }
}
