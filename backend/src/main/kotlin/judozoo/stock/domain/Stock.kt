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

@Entity
@Table(name = "stocks")
class Stock(
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

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isNotEmpty() && (q in name.lowercase() || shortCode.lowercase().startsWith(q))
    }

    internal fun relevance(q: String): Int = when {
        name.lowercase().startsWith(q) -> 0
        shortCode.lowercase().startsWith(q) -> 1
        else -> 2
    }
}
