package judozoo.stock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "overseas_stocks")
class OverseasStock(
    val exchange: String,
    val symbol: String,
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

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return q in name.lowercase() || q in englishName.lowercase() || q in symbol.lowercase()
    }
}
