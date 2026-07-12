package at.backend.watchlist.domain

import at.backend.library.jpa.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table

/**
 * 사용자가 직접 큐레이션하는 관심 테마. 키움이 분류하는 [at.backend.theme] 패키지의 테마와는 별개다.
 * 종목은 추가한 순서대로 보여준다.
 */
@Entity
@Table(name = "watch_theme")
class WatchTheme(

    @Column(nullable = false, length = 40)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @OneToMany(mappedBy = "theme", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder asc")
    private val stockList: MutableList<WatchThemeStock> = mutableListOf()

    val stocks: List<WatchThemeStock> get() = stockList.toList()

    /** 이미 담긴 종목이면 무시한다(중복 추가 방지). */
    fun addStock(stockCode: String, stockName: String) {
        if (stockList.any { it.stockCode == stockCode }) return
        stockList += WatchThemeStock(
            theme = this,
            stockCode = stockCode,
            stockName = stockName,
            sortOrder = (stockList.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
    }

    fun removeStock(stockCode: String) {
        stockList.removeIf { it.stockCode == stockCode }
    }

    /** [orderedCodes] 순서대로 재배치한다. 목록에 없는 코드는 무시하고, 빠진 종목은 뒤에 그대로 남는다. */
    fun reorderStocks(orderedCodes: List<String>) {
        val rank = orderedCodes.withIndex().associate { (i, code) -> code to i }
        stockList.sortBy { rank[it.stockCode] ?: Int.MAX_VALUE }
        stockList.forEachIndexed { i, stock -> stock.sortOrder = i }
    }
}
