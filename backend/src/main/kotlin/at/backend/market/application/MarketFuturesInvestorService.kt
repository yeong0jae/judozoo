package at.backend.market.application

import at.backend.market.domain.FuturesInvestorSnapshot
import at.backend.market.infrastructure.repository.FuturesInvestorSnapshotRepository
import at.backend.platform.kis.client.KisFuturesClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 코스피 선물 투자자 수급 — KIS가 주는 값이 '당일 누적'이라, 세션 순매수는 경계 스냅샷의 diff로 만든다.
 * 스냅샷 적재는 [FuturesInvestorPoller]가 장중에 수행한다. 폴러가 돌기 전 과거는 소급 불가.
 */
@Service
class MarketFuturesInvestorService(
    private val repository: FuturesInvestorSnapshotRepository,
) {

    @Transactional
    fun record(tradeDate: LocalDate, capturedAt: LocalDateTime, investors: KisFuturesClient.FuturesInvestors) {
        repository.save(
            FuturesInvestorSnapshot(
                tradeDate = tradeDate,
                capturedAt = capturedAt,
                foreignQty = investors.foreign,
                institutionQty = investors.institution,
                individualQty = investors.individual,
            ),
        )
    }

    /** 세션별(오전/오후/막판) 순매수(계약) — 스냅샷이 없는 세션은 nets=null. */
    @Transactional(readOnly = true)
    fun sessions(date: LocalDate): List<FuturesSessionNet> {
        val morning = snapshotAt(date, MORNING_END)
        val afternoon = snapshotAt(date, AFTERNOON_END)
        val close = snapshotAt(date, CLOSE)
        return listOf(
            FuturesSessionNet("오전", "08:45~12:00", morning?.nets()),
            FuturesSessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
            FuturesSessionNet("막판 동시호가", "15:00~15:45", diff(close, afternoon)),
        )
    }

    private fun snapshotAt(date: LocalDate, time: LocalTime) =
        repository.findFirstByTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(date, date.atTime(time))

    private fun diff(later: FuturesInvestorSnapshot?, earlier: FuturesInvestorSnapshot?): FuturesNets? {
        if (later == null || earlier == null) return null
        return later.nets() - earlier.nets()
    }

    private fun FuturesInvestorSnapshot.nets() = FuturesNets(foreignQty, institutionQty, individualQty)

    companion object {
        private val MORNING_END = LocalTime.of(12, 0)
        private val AFTERNOON_END = LocalTime.of(15, 0)
        private val CLOSE = LocalTime.of(15, 45)
    }
}

/** 투자자별 순매수(계약) 묶음 — 세션 diff 산술용. */
data class FuturesNets(
    val foreign: Long,
    val institution: Long,
    val individual: Long,
) {
    operator fun minus(o: FuturesNets) =
        FuturesNets(foreign - o.foreign, institution - o.institution, individual - o.individual)
}

data class FuturesSessionNet(
    val name: String,
    val time: String,
    val nets: FuturesNets?,
)
