package at.backend.watchlist.infrastructure.repository

import at.backend.watchlist.domain.WatchTheme
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface WatchThemeRepository : JpaRepository<WatchTheme, Long> {

    @EntityGraph(attributePaths = ["stockList"])
    fun findAllByOrderBySortOrderAsc(): List<WatchTheme>

    fun existsByName(name: String): Boolean
}
