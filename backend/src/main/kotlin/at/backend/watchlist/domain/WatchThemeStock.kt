package at.backend.watchlist.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** 관심 테마에 담긴 종목. 종목명은 담을 당시 이름을 그대로 둔다(개명돼도 카탈로그로 다시 맞출 수 있다). */
@Entity
@Table(
    name = "watch_theme_stock",
    uniqueConstraints = [UniqueConstraint(name = "uk_watch_theme_stock", columnNames = ["theme_id", "stock_code"])],
)
class WatchThemeStock(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theme_id", nullable = false)
    val theme: WatchTheme,

    @Column(name = "stock_code", nullable = false, length = 12)
    val stockCode: String,

    @Column(name = "stock_name", nullable = false, length = 50)
    val stockName: String,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}
