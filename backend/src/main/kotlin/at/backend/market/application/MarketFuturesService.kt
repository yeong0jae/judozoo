package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisFuturesClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 코스피 선물(근월물) 시세 — KIS 국내선물옵션. 종가베팅 지수 상세용.
 * 근월물 코드를 매 조회 시 전광판에서 뽑아, 기간별시세(요약+일봉)/분봉을 읽는다.
 */
@Service
class MarketFuturesService(
    private val client: KisFuturesClient,
    private val timeProvider: TimeProvider,
) {

    /** 근월물 시세 요약 — 선물·현물·베이시스·괴리율·미결제. 데이터 없으면 null. */
    @Cacheable("futuresQuote", unless = "#result == null") // 실패는 캐싱하지 않는다
    fun quote(): FuturesQuote? {
        val near = client.fetchNearMonth() ?: return null
        val today = timeProvider.today()
        val daily = client.fetchDaily(near.iscd, today.minusDays(10), today) ?: return null
        val s = daily.summary
        return FuturesQuote(
            futuresPrice = s.futuresPrice,
            changeRate = s.changeRate,
            spot = s.spot,
            basis = s.basis, // 시장 베이시스 = 선물 − 현물(KOSPI200)
            dprt = s.dprt,
            openInterest = s.openInterest,
            openInterestChange = s.openInterestChange,
            rmnnDays = near.rmnnDays,
            expiryDate = expiryOf(near.name) ?: "",
        )
    }

    /** 근월물 이름("F 202609")의 만기월 둘째 목요일 = KRX 파생 최종거래일. */
    private fun expiryOf(name: String): String? {
        val ym = Regex("(\\d{6})").find(name)?.value ?: return null
        val year = ym.substring(0, 4).toIntOrNull() ?: return null
        val month = ym.substring(4, 6).toIntOrNull() ?: return null
        val first = LocalDate.of(year, month, 1)
        val firstThu = first.plusDays(((DayOfWeek.THURSDAY.value - first.dayOfWeek.value + 7) % 7).toLong())
        return firstThu.plusWeeks(1).toString()
    }

    /** 근월물 캔들 — interval "1d"(최근 [count]봉)/"1m"(최근 [MINUTE_DAYS]거래일). */
    @Cacheable("futuresCandles", key = "#interval + ':' + #count", unless = "#result.isEmpty()")
    fun candles(interval: String, count: Int): List<KisFuturesClient.FuturesBar> {
        val near = client.fetchNearMonth() ?: return emptyList()
        val today = timeProvider.today()
        return when (interval) {
            "1d" -> client.fetchDaily(near.iscd, today.minusDays(count.toLong() * 2 + 10), today)?.candles ?: emptyList()
            "1m" -> recentMinutes(near.iscd)
            else -> emptyList()
        }
    }

    /**
     * 분봉 — 오늘부터 뒤로 밀며 최근 [MINUTE_DAYS]거래일치(주말·휴장·개장전 대응).
     * 휴장일을 요청하면 KIS가 직전 영업일 분봉을 주므로, 실제 반환된 날짜의 하루 전부터 다음 회차를 조회한다.
     * 종료시각은 항상 장 마감. 현재 시각을 넘기면 휴장일에 직전 영업일 분봉이 그 시각에서 잘려 온다.
     */
    private fun recentMinutes(iscd: String): List<KisFuturesClient.FuturesBar> {
        val bars = mutableListOf<KisFuturesClient.FuturesBar>()
        var day = timeProvider.today()
        var collected = 0
        repeat(MINUTE_DAYS + 5) {
            if (collected == MINUTE_DAYS) return bars.sortedBy { it.date + it.time }
            val dayBars = minutesOfDay(iscd, day, SESSION_END)
            val date = dayBars.firstOrNull()?.date
            if (date == null) {
                day = day.minusDays(1)
            } else {
                bars += dayBars
                collected++
                day = LocalDate.parse(date).minusDays(1)
            }
        }
        return bars.sortedBy { it.date + it.time }
    }

    /** [day] 하루치 분봉 — 한 번에 102봉만 오므로 [end]부터 장 시작까지 뒤로 페이징. */
    private fun minutesOfDay(iscd: String, day: LocalDate, end: LocalTime): List<KisFuturesClient.FuturesBar> {
        val byTime = sortedMapOf<String, KisFuturesClient.FuturesBar>()
        var hour = end
        repeat(MINUTE_PAGES) {
            val page = client.fetchMinute(iscd, day, hour)
            if (page.isEmpty()) return byTime.values.toList()
            page.forEach { byTime[it.time] = it }
            val earliest = LocalTime.parse(page.first().time)
            if (!earliest.isAfter(SESSION_START)) return byTime.values.toList()
            hour = earliest.minusMinutes(1)
        }
        return byTime.values.toList()
    }

    companion object {
        private const val MINUTE_DAYS = 2 // 분봉 수집 거래일 수
        private const val MINUTE_PAGES = 7 // 하루(08:45~15:45=420분)를 102봉씩 덮는 최대 페이지 수
        private val SESSION_START: LocalTime = LocalTime.of(8, 45) // 선물 개장(동시호가 08:30~08:45)
        private val SESSION_END: LocalTime = LocalTime.of(15, 45) // 조회 상한(마감 동시호가 체결까지 포함)
    }
}

/** 코스피 선물 시세 요약(억원 아님, 지수 포인트). */
data class FuturesQuote(
    val futuresPrice: Double,
    val changeRate: Double, // 선물 등락률(%)
    val spot: Double, // 현물 KOSPI200
    val basis: Double, // 선물 − 현물
    val dprt: Double, // 괴리율(%) — 선물이 이론가 대비 얼마나 고평가인가
    val openInterest: Long, // 미결제약정(계약)
    val openInterestChange: Long, // 전일 대비 증감
    val rmnnDays: Int, // 만기 잔존일수
    val expiryDate: String, // 만기일 yyyy-MM-dd (근월물 최종거래일)
)
