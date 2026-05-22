package at.backend.market.application

import at.backend.platform.kiwoom.client.KiwoomIndexClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * KOSPI 종합지수 한 시점 스냅샷 제공.
 * candidateStocks와 같은 짧은 TTL(5s) 캐시로 키움 직접 호출을 줄임.
 */
@Service
class KospiIndexService(
    private val indexClient: KiwoomIndexClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable("candidateStocks") // 5s TTL 캐시 재사용
    fun getKospi(): KospiIndexResult {
        val snap = indexClient.fetchIndex(KOSPI_CODE)
        if (snap == null) {
            log.warn("KOSPI index fetch returned null — 0으로 폴백")
            return KospiIndexResult(currentValue = 0.0, changeRate = 0.0)
        }
        return KospiIndexResult(currentValue = snap.currentValue, changeRate = snap.changeRate)
    }

    data class KospiIndexResult(
        val currentValue: Double,
        val changeRate: Double, // 단위: % (예: +0.42)
    )

    companion object {
        private const val KOSPI_CODE = "001"
    }
}
