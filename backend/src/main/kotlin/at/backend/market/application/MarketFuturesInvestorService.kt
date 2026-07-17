package at.backend.market.application

import at.backend.market.domain.FuturesInvestorSnapshot
import at.backend.market.infrastructure.repository.FuturesInvestorSnapshotRepository
import at.backend.platform.kis.client.KisFuturesClient
import org.springframework.data.domain.PageRequest
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
    private val holidayService: HolidayService,
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
                securitiesQty = investors.securities,
                insuranceQty = investors.insurance,
                merchantBankQty = investors.merchantBank,
                trustQty = investors.trust,
                privateEquityQty = investors.privateEquity,
                fundQty = investors.fund,
                bankQty = investors.bank,
                otherOrgQty = investors.otherOrg,
                otherCorpQty = investors.otherCorp,
            ),
        )
    }

    /**
     * 최근 [count] 거래일 일별 순매수(계약). 각 거래일의 마지막 스냅샷 = 그날의 당일 누적. 최신순.
     * 폴러가 적재한 날만 나온다(과거 소급 불가). 당일은 장중이면 진행 중인 누적값.
     * 휴장일에 잘못 적재된 스냅샷은 제외한다(폴러가 공휴일 인식 전 쌓았을 수 있음). 여유분을 더 읽어 필터 후 count만큼.
     */
    @Transactional(readOnly = true)
    fun dailyHistory(count: Int): List<FuturesInvestorDay> =
        repository.findDailyLatest(PageRequest.of(0, count + HOLIDAY_MARGIN))
            .filter { holidayService.isOpen(it.tradeDate) != false }
            .take(count)
            .map { FuturesInvestorDay(it.tradeDate, it.nets()) }

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

    private fun FuturesInvestorSnapshot.nets() = FuturesNets(
        foreign = foreignQty,
        institution = institutionQty,
        individual = individualQty,
        otherCorp = otherCorpQty,
        breakdown = FuturesOrg(
            securities = securitiesQty,
            insurance = insuranceQty,
            merchantBank = merchantBankQty,
            trust = trustQty,
            privateEquity = privateEquityQty,
            fund = fundQty,
            bank = bankQty,
            otherOrg = otherOrgQty,
        ),
    )

    companion object {
        private const val HOLIDAY_MARGIN = 5 // 휴장일 스냅샷을 걸러내도 count를 채우도록 더 읽는 여유분
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
    val otherCorp: Long,
    val breakdown: FuturesOrg,
) {
    operator fun minus(o: FuturesNets) = FuturesNets(
        foreign - o.foreign,
        institution - o.institution,
        individual - o.individual,
        otherCorp - o.otherCorp,
        breakdown - o.breakdown,
    )
}

/** 세션 기관 세부 순매수(계약) — KIS 선물 분류. 표시 순서: 증권·보험·종금·투신·사모펀드·기금·은행·기타단체. */
data class FuturesOrg(
    val securities: Long,
    val insurance: Long,
    val merchantBank: Long,
    val trust: Long,
    val privateEquity: Long,
    val fund: Long,
    val bank: Long,
    val otherOrg: Long,
) {
    operator fun minus(o: FuturesOrg) = FuturesOrg(
        securities - o.securities,
        insurance - o.insurance,
        merchantBank - o.merchantBank,
        trust - o.trust,
        privateEquity - o.privateEquity,
        fund - o.fund,
        bank - o.bank,
        otherOrg - o.otherOrg,
    )
}

data class FuturesSessionNet(
    val name: String,
    val time: String,
    val nets: FuturesNets?,
)

/** 하루치 선물 투자자 순매수(계약) — 그날 마지막 스냅샷의 당일 누적. */
data class FuturesInvestorDay(
    val date: LocalDate,
    val nets: FuturesNets,
)
