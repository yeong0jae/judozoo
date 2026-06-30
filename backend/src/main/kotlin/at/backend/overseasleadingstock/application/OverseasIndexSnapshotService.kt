package at.backend.overseasleadingstock.application

import at.backend.overseasleadingstock.domain.OverseasIndexCloseSnapshot
import at.backend.overseasleadingstock.infrastructure.repository.OverseasIndexCloseSnapshotRepository
import at.backend.platform.kis.client.KisOverseasIndexClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/** 해외지수 장 마감 스냅샷 적재/조회. 적재는 마감 캡처가, 조회는 타임라인이 쓴다. */
@Service
class OverseasIndexSnapshotService(
    private val repository: OverseasIndexCloseSnapshotRepository,
    private val indexClient: KisOverseasIndexClient,
) {

    /**
     * 나스닥종합 마감 시세를 한 행 적재. 영업일은 응답의 실제 영업일(output2 최신)을 따른다.
     * 이미 적재됐거나(멱등) 조회 실패면 스킵. 적재했으면 1.
     */
    @Transactional
    fun capture(now: LocalDateTime): Int {
        val to = now.toLocalDate()
        val quote = indexClient.fetchIndexDailyClose(NASDAQ_COMPOSITE, to.minusDays(LOOKBACK_DAYS), to)
            ?: return 0
        if (repository.existsByCodeAndTradeDate(quote.code, quote.tradeDate)) return 0
        repository.save(
            OverseasIndexCloseSnapshot(
                code = quote.code,
                name = quote.name,
                tradeDate = quote.tradeDate,
                capturedAt = now,
                indexValue = quote.price,
                changeRate = quote.changeRate,
            ),
        )
        return 1
    }

    @Transactional(readOnly = true)
    fun snapshotsOn(date: LocalDate): List<OverseasIndexCloseSnapshot> =
        repository.findByTradeDate(date)

    companion object {
        private const val NASDAQ_COMPOSITE = "COMP"
        private const val LOOKBACK_DAYS = 7L // output1(최신 종가)만 쓰지만 조회 구간 확보용
    }
}
