package at.backend.overseasleadingstock.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 해외지수(나스닥종합 등) 장 마감 스냅샷 — 타임라인에 하루 한 줄로 찍힌다.
 * (지수코드, 영업일)별 한 행만 존재한다(같은 날 재캡처는 스킵). 미국 휴장일엔 영업일이 안 늘어 자연 멱등.
 */
@Entity
@Table(
    name = "overseas_index_close_snapshot",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_overseas_index_close_snapshot", columnNames = ["code", "trade_date"]),
    ],
)
class OverseasIndexCloseSnapshot(

    @Column(nullable = false)
    val code: String,

    @Column(nullable = false)
    val name: String,

    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,

    @Column(name = "captured_at", nullable = false)
    val capturedAt: LocalDateTime,

    @Column(name = "index_value", nullable = false)
    val indexValue: Double,

    @Column(name = "change_rate", nullable = false)
    val changeRate: Double,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
