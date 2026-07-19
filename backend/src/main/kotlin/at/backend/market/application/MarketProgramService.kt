package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.market.domain.ProgramTradeSnapshot
import at.backend.market.infrastructure.repository.ProgramTradeSnapshotRepository
import at.backend.platform.kiwoom.client.KiwoomProgramClient
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 시장(코스피/코스닥) 프로그램 매매 — ka90010이 주는 '당일 누적'을 폴러가 스냅샷으로 찍어(=시장 수급과 동일 방식),
 * 세션(오전/오후/막판) 순매수는 경계 스냅샷의 diff로 만든다. 최근 일별은 ka90010 과거 행을 그대로 쓴다.
 */
@Service
class MarketProgramService(
    private val client: KiwoomProgramClient,
    private val repository: ProgramTradeSnapshotRepository,
    private val holidayService: HolidayService,
    private val timeProvider: TimeProvider,
) {

    @Transactional
    fun record(market: Market, tradeDate: LocalDate, capturedAt: LocalDateTime, point: KiwoomProgramClient.MarketProgramPoint) {
        repository.save(
            ProgramTradeSnapshot(
                market = market,
                tradeDate = tradeDate,
                capturedAt = capturedAt,
                arbitrageMil = point.arbitrageNet,
                nonArbitrageMil = point.nonArbitrageNet,
                totalMil = point.totalNet,
            ),
        )
    }

    /** 세션별 프로그램 순매수(억원) — 당일 누적 스냅샷 경계 diff. 없는 세션은 nets=null. */
    @Transactional(readOnly = true)
    fun sessions(market: Market, date: LocalDate): List<ProgramSessionNet> {
        val open = snapshotAt(market, date, OPEN)
        val morning = snapshotAt(market, date, MORNING_END)
        val afternoon = snapshotAt(market, date, AFTERNOON_END)
        val close = snapshotAt(market, date, CLOSE)
        val afterClose = snapshotAt(market, date, AFTER_END)
        // open이 있으면 오전은 순수 정규장, 없으면 프리 포함 누적으로 폴백(과거 스냅샷 호환).
        val morningNet = if (open != null) diff(morning, open) else morning?.nets()
        return listOf(
            ProgramSessionNet("프리마켓", "08:00~09:00", open?.nets()),
            ProgramSessionNet("오전", "09:00~12:00", morningNet),
            ProgramSessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
            ProgramSessionNet("막판 동시호가", "15:00~15:40", diff(close, afternoon)),
            ProgramSessionNet("애프터마켓", "15:40~20:00", diff(afterClose, close)),
        )
    }

    /** 최근 [count] 거래일 일별 프로그램 순매수(억원) — ka90010 과거 행. 휴장일은 제외. 최신순. */
    fun dailyHistory(market: Market, count: Int): List<ProgramDay> =
        client.fetchMarketProgramDaily(market, timeProvider.today())
            .mapNotNull { p -> p.date?.let { it to p } }
            .filter { (date, _) -> holidayService.isOpen(date) != false }
            .take(count)
            .map { (date, p) ->
                ProgramDay(date, p.arbitrageNet.toEok(), p.nonArbitrageNet.toEok(), p.totalNet.toEok())
            }

    private fun snapshotAt(market: Market, date: LocalDate, time: LocalTime) =
        repository.findFirstByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(market, date, date.atTime(time))

    private fun diff(later: ProgramTradeSnapshot?, earlier: ProgramTradeSnapshot?): ProgramNets? {
        if (later == null || earlier == null) return null
        return later.nets() - earlier.nets()
    }

    private fun ProgramTradeSnapshot.nets() =
        ProgramNets(arbitrageMil.toEok(), nonArbitrageMil.toEok(), totalMil.toEok())

    /** 백만원 → 억원. */
    private fun Long.toEok() = this / 100

    companion object {
        private val OPEN = LocalTime.of(9, 0)
        private val MORNING_END = LocalTime.of(12, 0)
        private val AFTERNOON_END = LocalTime.of(15, 0)
        private val CLOSE = LocalTime.of(15, 40)
        private val AFTER_END = LocalTime.of(20, 0)
    }
}

/** 프로그램 순매수 묶음(억원). */
data class ProgramNets(
    val arbitrageEok: Long,     // 차익
    val nonArbitrageEok: Long,  // 비차익
    val totalEok: Long,         // 전체
) {
    operator fun minus(o: ProgramNets) =
        ProgramNets(arbitrageEok - o.arbitrageEok, nonArbitrageEok - o.nonArbitrageEok, totalEok - o.totalEok)
}

data class ProgramSessionNet(
    val name: String,
    val time: String,
    val nets: ProgramNets?,
)

/** 하루치 프로그램 순매수(억원). */
data class ProgramDay(
    val date: LocalDate,
    val arbitrageEok: Long,
    val nonArbitrageEok: Long,
    val totalEok: Long,
)
