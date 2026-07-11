package at.backend.market.application

import at.backend.market.domain.MarketInvestorTrading
import at.backend.market.infrastructure.repository.MarketInvestorTradingRepository
import at.backend.platform.toss.client.TossMarketIndicatorClient
import at.backend.platform.toss.client.TossMarketIndicatorClient.MarketInvestorRecord
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 시장(코스피/코스닥) 투자자 매매대금 — 토스 조회 + 장중 스냅샷 적재/조회 + 세션(오전/오후/막판) 산출.
 * 세션은 API가 직접 주지 않아, 당일 누적 스냅샷을 경계 시각에 뽑아 차이(diff)로 계산한다.
 */
@Service
class MarketInvestorService(
    private val repository: MarketInvestorTradingRepository,
    private val client: TossMarketIndicatorClient,
) {

    /** 폴러가 받은 당일 레코드를 스냅샷 한 줄로 적재. */
    @Transactional
    fun recordSnapshot(market: Market, capturedAt: LocalDateTime, rec: MarketInvestorRecord) {
        val b = rec.breakdown
        repository.save(
            MarketInvestorTrading(
                market = market,
                tradeDate = rec.date,
                capturedAt = capturedAt,
                sourceUpdatedAt = rec.sourceUpdatedAt,
                individualEok = rec.individualNetEok,
                foreignEok = rec.foreignNetEok,
                institutionEok = rec.institutionNetEok,
                otherCorpEok = rec.otherCorpNetEok,
                pensionFundEok = b.pensionFundEok,
                trustEok = b.trustEok,
                financialInvestmentEok = b.financialInvestmentEok,
                privateEquityEok = b.privateEquityEok,
                insuranceEok = b.insuranceEok,
                bankEok = b.bankEok,
                otherFinanceEok = b.otherFinanceEok,
            ),
        )
    }

    /** 그날 장중 스냅샷 전체(시각 오름차순). 갱신주기 확인·누적 곡선용. */
    @Transactional(readOnly = true)
    fun intraday(market: Market, date: LocalDate): List<MarketInvestorTrading> =
        repository.findByMarketAndTradeDateOrderByCapturedAtAsc(market, date)

    /** 세션별(오전/오후/막판) 순매수 — 당일 누적 스냅샷의 경계 diff. 데이터 없는 세션은 nets=null. */
    @Transactional(readOnly = true)
    fun sessions(market: Market, date: LocalDate): List<SessionNet> {
        val morning = snapshotAt(market, date, MORNING_END)
        val afternoon = snapshotAt(market, date, AFTERNOON_END)
        val close = snapshotAt(market, date, CLOSE)
        return listOf(
            SessionNet("오전", "09:00~12:00", morning?.nets()),
            SessionNet("오후", "12:00~14:40", diff(afternoon, morning)),
            SessionNet("막판 동시호가", "14:40~15:30", diff(close, afternoon)),
        )
    }

    /** 최근 [count]일 일별 순매수(외/기/개/기타법인 + 기관 세부). 토스 직접 조회. */
    fun dailyHistory(market: Market, count: Int): List<MarketInvestorRecord> =
        client.fetchInvestorTrading(market.name, interval = "1d", count = count)

    private fun snapshotAt(market: Market, date: LocalDate, at: LocalTime): MarketInvestorTrading? =
        repository.findFirstByMarketAndTradeDateAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
            market, date, date.atTime(at),
        )

    private fun diff(later: MarketInvestorTrading?, earlier: MarketInvestorTrading?): Nets? {
        if (later == null || earlier == null) return null
        return later.nets() - earlier.nets()
    }

    companion object {
        private val MORNING_END = LocalTime.of(12, 0)
        private val AFTERNOON_END = LocalTime.of(14, 40)
        private val CLOSE = LocalTime.of(15, 30)
    }
}

/** 투자자별 순매수(억원) 묶음 — 세션 diff 산술용. */
data class Nets(
    val individual: Long,
    val foreign: Long,
    val institution: Long,
    val otherCorp: Long,
) {
    operator fun minus(o: Nets) = Nets(
        individual - o.individual,
        foreign - o.foreign,
        institution - o.institution,
        otherCorp - o.otherCorp,
    )
}

fun MarketInvestorTrading.nets() = Nets(individualEok, foreignEok, institutionEok, otherCorpEok)

data class SessionNet(
    val name: String,
    val time: String,
    val nets: Nets?,
)
