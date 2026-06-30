package at.backend.overseasleadingstock.infrastructure.repository

import at.backend.overseasleadingstock.domain.OverseasIndexCloseSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface OverseasIndexCloseSnapshotRepository : JpaRepository<OverseasIndexCloseSnapshot, Long> {

    fun findByTradeDate(tradeDate: LocalDate): List<OverseasIndexCloseSnapshot>

    fun existsByCodeAndTradeDate(code: String, tradeDate: LocalDate): Boolean
}
