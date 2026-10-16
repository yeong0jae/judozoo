package judozoo.stock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 해외 상장 종목(미국). 국내 [Stock]과 테이블을 나눈 이유: 식별자가 종목코드가 아니라 (거래소, 심볼) 쌍이고,
 * 시세 조회 경로도 다르다. 갱신은 [Stock]과 같이 전체 교체다.
 */
@Entity
@Table(name = "overseas_stocks")
class OverseasStock(
    /** NAS / NYS / AMS */
    val exchange: String,
    val symbol: String,
    /** 한글명 */
    val name: String,
    @Column(name = "english_name")
    val englishName: String,
    @Column(name = "created_at")
    val createdAt: LocalDateTime,
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null

    /** 종목명(한글·영문)이나 심볼에 질의가 들어가면 매칭. 대소문자 무시. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return q in name.lowercase() || q in englishName.lowercase() || q in symbol.lowercase()
    }
}
