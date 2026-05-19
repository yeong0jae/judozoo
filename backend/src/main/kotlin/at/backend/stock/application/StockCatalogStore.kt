package at.backend.stock.application

import at.backend.stock.domain.Stock
import at.backend.stock.infrastructure.repository.StockJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 종목 카탈로그의 영속/조회. 갱신은 항상 전체 교체. */
@Service
class StockCatalogStore(private val repository: StockJpaRepository) {

    /** 가장 최근 row의 생성일 = 마지막 갱신일. 비어 있으면 null. */
    fun lastSyncedDate(): LocalDate? =
        repository.findTopByOrderByCreatedAtDesc()?.createdAt?.toLocalDate()

    fun loadAll(): List<Stock> = repository.findAll()

    @Transactional
    fun replaceAll(stocks: List<Stock>) {
        repository.deleteAllInBatch()
        repository.saveAll(stocks)
    }
}
