package at.backend.market.application

import at.backend.platform.kiwoom.client.KiwoomIndexClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * KOSPI·KOSDAQ 종합지수 한 시점 스냅샷 제공.
 * candidateStocks와 같은 짧은 TTL(5s) 캐시로 키움 직접 호출을 줄임.
 */
@Service
class KospiIndexService(
    private val indexClient: KiwoomIndexClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable("kospiIndex") // 5s TTL — 후보 캐시와 분리 (이전엔 같은 이름 공유로 서로 evict)
    fun getKospi(): IndexResult = fetch(KOSPI_CODE, mrktTp = "0", name = "KOSPI")

    @Cacheable("kosdaqIndex")
    fun getKosdaq(): IndexResult = fetch(KOSDAQ_CODE, mrktTp = "1", name = "KOSDAQ")

    private fun fetch(code: String, mrktTp: String, name: String): IndexResult {
        val snap = indexClient.fetchIndex(code, mrktTp)
        if (snap == null) {
            log.warn("{} index fetch returned null — 0으로 폴백", name)
            return IndexResult(currentValue = 0.0, changeRate = 0.0)
        }
        return IndexResult(currentValue = snap.currentValue, changeRate = snap.changeRate)
    }

    data class IndexResult(
        val currentValue: Double,
        val changeRate: Double, // 단위: % (예: +0.42)
    )

    companion object {
        private const val KOSPI_CODE = "001"
        private const val KOSDAQ_CODE = "101"
    }
}
