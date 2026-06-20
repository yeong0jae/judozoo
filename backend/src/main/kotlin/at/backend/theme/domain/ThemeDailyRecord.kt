package at.backend.theme.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

/**
 * 하루치 "강한 테마" 한 줄 — 일자별 상위 N개를 적재해 테마 순환을 캘린더로 본다.
 * [rank]는 그날 거래대금 상위 순위(1=가장 많음), [tradingValue]는 해당 테마 소속 상위 종목의
 * 거래대금 합산(원). 과거 일자별 데이터 API가 없어 매일 캡처해 누적하는 구조라 백필은 불가.
 */
@Entity
@Table(
    name = "theme_daily",
    uniqueConstraints = [UniqueConstraint(columnNames = ["date", "theme_name"])],
    indexes = [Index(name = "idx_theme_daily_date", columnList = "date")],
)
class ThemeDailyRecord(

    @Column(nullable = false)
    val date: LocalDate,

    // 컬럼명 rank는 MySQL 8.0 예약어(RANK())라 DDL 생성이 실패함 → theme_rank로 매핑
    @Column(name = "theme_rank", nullable = false)
    val rank: Int,

    @Column(nullable = false)
    val themeName: String,

    @Column(nullable = false)
    val tradingValue: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
