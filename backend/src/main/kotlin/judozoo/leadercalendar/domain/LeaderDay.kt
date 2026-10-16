package judozoo.leadercalendar.domain

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
@Table(name = "leader_day")
class LeaderDay(
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "enum('KR','US')")
    val region: Region,
    @Column(name = "trade_date") val tradeDate: LocalDate,
    @Column(name = "taken_at") val takenAt: LocalDateTime,
    val closed: Boolean,
    @Column(name = "created_at") val createdAt: LocalDateTime = takenAt,
    @Column(name = "updated_at") val updatedAt: LocalDateTime = takenAt,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
