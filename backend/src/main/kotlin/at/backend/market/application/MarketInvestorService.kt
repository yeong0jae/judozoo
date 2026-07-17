package at.backend.market.application

import at.backend.leadingstock.application.MarketSignalEventService
import at.backend.leadingstock.domain.MarketInvestorSnapshot
import at.backend.library.time.TimeProvider
import at.backend.platform.kiwoom.client.KiwoomSectorInvestorClient
import at.backend.stock.domain.Market
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 시장(코스피/코스닥) 투자자 순매수 — 전부 키움 ka10051 기반.
 * 일별 히스토리는 base_dt를 날짜별로 조회하고, 세션(시간대)은 폴러가 적재한 당일 누적 스냅샷의 경계 diff로 계산한다.
 */
@Service
class MarketInvestorService(
    private val kiwoom: KiwoomSectorInvestorClient,
    private val signalEventService: MarketSignalEventService,
    private val holidayService: HolidayService,
    private val timeProvider: TimeProvider,
) {

    /**
     * 최근 [count] 거래일 일별 순매수(외/기/개/기타법인 + 기관 세부). 키움 ka10051을 base_dt로 날짜별 조회.
     * 주말·공휴일은 건너뛴다 — 휴장일에 base_dt로 부르면 직전 영업일 값이 그대로 와 중복되기 때문. 최신순.
     */
    fun dailyHistory(market: Market, count: Int): List<MarketInvestorDay> {
        val mrktTp = market.mrktTp()
        val out = mutableListOf<MarketInvestorDay>()
        var day = timeProvider.today()
        var guard = 0
        while (out.size < count && guard < count * 3 + 10) {
            guard++
            val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
            if (weekend || holidayService.isOpen(day) == false) {
                day = day.minusDays(1)
                continue
            }
            kiwoom.fetchSectorNetBuy(mrktTp, day.format(DATE_FMT))?.let { out += MarketInvestorDay.of(day, it) }
            day = day.minusDays(1)
        }
        return out
    }

    /**
     * 세션별 순매수 — 당일 누적 스냅샷 경계 diff. 데이터 없는 세션은 nets=null.
     * 통합(KRX+NXT) 기준이라 프리마켓(08:00~09:00)·애프터마켓(15:40~20:00)도 일별 누적에 들어간다.
     * 다섯 구간의 합 = 그날 최종 누적(= 최근 10일 표의 그날 값). 폴러가 프리 스냅샷을 남기기 전 과거는
     * 프리 경계가 없어, 프리는 "집계 전"으로 두고 오전에 프리를 포함(예전 동작)해 합을 보존한다.
     */
    fun sessions(market: Market, date: LocalDate): List<SessionNet> {
        val open = signalEventService.investorSnapshotAt(market, date.atTime(OPEN))
        val morning = signalEventService.investorSnapshotAt(market, date.atTime(MORNING_END))
        val afternoon = signalEventService.investorSnapshotAt(market, date.atTime(AFTERNOON_END))
        val close = signalEventService.investorSnapshotAt(market, date.atTime(CLOSE))
        val afterClose = signalEventService.investorSnapshotAt(market, date.atTime(AFTER_END))
        // open이 있으면 오전은 순수 정규장(09:00~12:00), 없으면 프리 포함 누적으로 폴백.
        val morningNet = if (open != null) diff(morning, open) else morning?.nets()
        return listOf(
            SessionNet("프리마켓", "08:00~09:00", open?.nets()),
            SessionNet("오전", "09:00~12:00", morningNet),
            SessionNet("오후", "12:00~15:00", diff(afternoon, morning)),
            SessionNet("막판 동시호가", "15:00~15:40", diff(close, afternoon)),
            SessionNet("애프터마켓", "15:40~20:00", diff(afterClose, close)),
        )
    }

    private fun diff(later: MarketInvestorSnapshot?, earlier: MarketInvestorSnapshot?): Nets? {
        if (later == null || earlier == null) return null
        return later.nets() - earlier.nets()
    }

    private fun Market.mrktTp() = when (this) {
        Market.KOSPI -> "0"
        Market.KOSDAQ -> "1"
    }

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val OPEN = LocalTime.of(9, 0)
        private val MORNING_END = LocalTime.of(12, 0)
        private val AFTERNOON_END = LocalTime.of(15, 0)
        private val CLOSE = LocalTime.of(15, 40)
        private val AFTER_END = LocalTime.of(20, 0)
    }
}

/** 투자자별 순매수(억원) 묶음 — 세션 diff 산술용. */
data class Nets(
    val individual: Long,
    val foreign: Long,
    val institution: Long,
    val otherCorp: Long,
    val breakdown: SessionOrg,
) {
    operator fun minus(o: Nets) = Nets(
        individual - o.individual,
        foreign - o.foreign,
        institution - o.institution,
        otherCorp - o.otherCorp,
        breakdown - o.breakdown,
    )
}

/**
 * 세션 기관 세부(억원) — 일별(ka10051)과 동일한 7종. 표시 순서: 금융투자·보험·기타금융·투신·사모펀드·연기금등·은행.
 * 기타금융은 스냅샷 컬럼 추가 이후 폴부터 채워진다(과거 스냅샷은 0).
 */
data class SessionOrg(
    val financialInvestmentEok: Long,
    val insuranceEok: Long,
    val otherFinanceEok: Long,
    val trustEok: Long,
    val privateEquityEok: Long,
    val pensionFundEok: Long,
    val bankEok: Long,
) {
    operator fun minus(o: SessionOrg) = SessionOrg(
        financialInvestmentEok - o.financialInvestmentEok,
        insuranceEok - o.insuranceEok,
        otherFinanceEok - o.otherFinanceEok,
        trustEok - o.trustEok,
        privateEquityEok - o.privateEquityEok,
        pensionFundEok - o.pensionFundEok,
        bankEok - o.bankEok,
    )
}

fun MarketInvestorSnapshot.nets() = Nets(
    individual = individualEok,
    foreign = foreignEok,
    institution = institutionEok,
    otherCorp = otherCorpEok,
    breakdown = SessionOrg(
        financialInvestmentEok = financialInvestmentEok,
        insuranceEok = insuranceEok,
        otherFinanceEok = otherFinanceEok,
        trustEok = trustEok,
        privateEquityEok = privateEquityEok,
        pensionFundEok = pensionFundEok,
        bankEok = bankEok,
    ),
)

data class SessionNet(
    val name: String,
    val time: String,
    val nets: Nets?,
)

/** 기관 세부 순매수(억원) — 키움 7종. */
data class OrgBreakdown(
    val financialInvestmentEok: Long,
    val trustEok: Long,
    val pensionFundEok: Long,
    val privateEquityEok: Long,
    val insuranceEok: Long,
    val bankEok: Long,
    val otherFinanceEok: Long,
)

/** 하루치 시장 투자자 순매수. */
data class MarketInvestorDay(
    val date: LocalDate,
    val individualEok: Long,
    val foreignEok: Long,
    val institutionEok: Long,
    val otherCorpEok: Long,
    val breakdown: OrgBreakdown,
) {
    companion object {
        fun of(date: LocalDate, nb: KiwoomSectorInvestorClient.SectorInvestorNetBuy) = MarketInvestorDay(
            date = date,
            individualEok = nb.individualEok,
            foreignEok = nb.foreignEok,
            institutionEok = nb.institutionEok,
            otherCorpEok = nb.otherCorpEok,
            breakdown = OrgBreakdown(
                financialInvestmentEok = nb.financialInvestmentEok,
                trustEok = nb.trustEok,
                pensionFundEok = nb.pensionFundEok,
                privateEquityEok = nb.privateEquityEok,
                insuranceEok = nb.insuranceEok,
                bankEok = nb.bankEok,
                otherFinanceEok = nb.otherFinanceEok,
            ),
        )
    }
}
