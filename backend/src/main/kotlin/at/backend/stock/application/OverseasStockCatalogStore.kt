package at.backend.stock.application

import at.backend.stock.domain.OverseasStock
import at.backend.stock.infrastructure.repository.OverseasStockJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Component
class OverseasStockCatalogStore(private val repository: OverseasStockJpaRepository) {

    /** 가장 최근 row의 생성일 = 마지막 갱신일. 비어 있으면 null. */
    fun lastSyncedDate(): LocalDate? =
        repository.findTopByOrderByCreatedAtDesc()?.createdAt?.toLocalDate()

    fun loadAll(): List<OverseasStock> = repository.findAll()

    @Transactional
    fun replaceAll(stocks: List<OverseasStock>) {
        repository.deleteAllInBatch()
        repository.saveAll(stocks)
    }
}
