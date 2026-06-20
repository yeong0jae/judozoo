package at.backend.theme.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 특정 테마-일자([themeDailyId])에 거래대금을 기여한 종목 한 줄.
 * 그날 거래대금 상위 종목 중 해당 테마에 속한 것 = 테마에 돈을 몰아준 종목.
 */
@Entity
@Table(
    name = "theme_daily_stock",
    indexes = [
        Index(name = "idx_theme_daily_stock_pid", columnList = "theme_daily_id"),
        Index(name = "idx_theme_daily_stock_date", columnList = "date"),
    ],
)
class ThemeDailyStock(

    @Column(name = "theme_daily_id", nullable = false)
    val themeDailyId: Long,

    @Column(nullable = false)
    val date: LocalDate,

    @Column(nullable = false)
    val stockCode: String,

    @Column(nullable = false)
    val stockName: String,

    @Column(nullable = false)
    val tradingValue: Long,

    // 캡처 시점 당일 등락률(%). 기능 추가 전 적재분은 null.
    @Column
    val priceChangeRate: Double? = null,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
