package at.backend.watchlist.application

import at.backend.watchlist.domain.WatchTheme
import at.backend.watchlist.infrastructure.repository.WatchThemeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자가 직접 만드는 관심 테마 — 테마·종목 추가/삭제.
 * 종목 시세는 [WatchThemeQuoteService]가 따로 물린다(선택한 테마의 종목만 조회해 키움 호출을 아낀다).
 */
@Service
class WatchThemeService(
    private val repository: WatchThemeRepository,
) {

    @Transactional(readOnly = true)
    fun findAll(): List<WatchThemeView> = repository.findAllByOrderBySortOrderAsc().map { it.toView() }

    @Transactional
    fun createTheme(name: String): WatchThemeView {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "테마 이름이 비어 있습니다" }
        require(!repository.existsByName(trimmed)) { "이미 있는 테마입니다: $trimmed" }
        val nextOrder = repository.findAllByOrderBySortOrderAsc().lastOrNull()?.sortOrder?.plus(1) ?: 0
        return repository.save(WatchTheme(name = trimmed, sortOrder = nextOrder)).toView()
    }

    @Transactional
    fun deleteTheme(themeId: Long) = repository.deleteById(themeId)

    @Transactional
    fun addStock(themeId: Long, stockCode: String, stockName: String): WatchThemeView {
        val theme = repository.findById(themeId).orElseThrow { IllegalArgumentException("없는 테마입니다: $themeId") }
        theme.addStock(stockCode, stockName)
        return theme.toView()
    }

    /** [orderedIds] 순서대로 테마를 재배치한다. */
    @Transactional
    fun reorderThemes(orderedIds: List<Long>) {
        val rank = orderedIds.withIndex().associate { (i, id) -> id to i }
        repository.findAllByOrderBySortOrderAsc()
            .sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            .forEachIndexed { i, theme -> theme.sortOrder = i }
    }

    @Transactional
    fun reorderStocks(themeId: Long, orderedCodes: List<String>): WatchThemeView {
        val theme = repository.findById(themeId).orElseThrow { IllegalArgumentException("없는 테마입니다: $themeId") }
        theme.reorderStocks(orderedCodes)
        return theme.toView()
    }

    @Transactional
    fun removeStock(themeId: Long, stockCode: String): WatchThemeView {
        val theme = repository.findById(themeId).orElseThrow { IllegalArgumentException("없는 테마입니다: $themeId") }
        theme.removeStock(stockCode)
        return theme.toView()
    }

    private fun WatchTheme.toView() = WatchThemeView(
        id = id,
        name = name,
        stocks = stocks.map { WatchStockView(it.stockCode, it.stockName) },
    )
}

/** 관심 테마 — 시세는 담기지 않는다(별도 조회). */
data class WatchThemeView(
    val id: Long,
    val name: String,
    val stocks: List<WatchStockView>,
)

data class WatchStockView(
    val stockCode: String,
    val stockName: String,
)
