package at.backend.theme.infrastructure.repository

import at.backend.theme.domain.ThemeDailyStock
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface ThemeDailyStockRepository : JpaRepository<ThemeDailyStock, Long> {

    fun findByThemeDailyIdInOrderByTradingValueDesc(themeDailyIds: List<Long>): List<ThemeDailyStock>

    fun deleteByDate(date: LocalDate)
}
