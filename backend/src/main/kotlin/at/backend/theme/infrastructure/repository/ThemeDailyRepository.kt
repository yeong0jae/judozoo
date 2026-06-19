package at.backend.theme.infrastructure.repository

import at.backend.theme.domain.ThemeDailyRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface ThemeDailyRepository : JpaRepository<ThemeDailyRecord, Long> {

    fun findByDateBetweenOrderByDateAscRankAsc(from: LocalDate, to: LocalDate): List<ThemeDailyRecord>

    fun deleteByDate(date: LocalDate)
}
