package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisFuturesClient
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
    fun quote(): FuturesQuote? {
        val near = client.fetchNearMonth() ?: return null
        val today = timeProvider.today()
        val daily = client.fetchDaily(near.iscd, today.minusDays(10), today) ?: return null
        val s = daily.summary
        return FuturesQuote(
            futuresPrice = s.futuresPrice,
            changeRate = s.changeRate,
            spot = s.spot,
            spotChangeRate = s.spotChangeRate,
            basis = s.futuresPrice - s.spot, // 시장 베이시스 = 선물 − 현물(KOSPI200)
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

    /** 근월물 캔들 — interval "1d"(최근 [count]봉)/"1m"(최근 1거래일). */
    fun candles(interval: String, count: Int): List<KisFuturesClient.FuturesBar> {
        val near = client.fetchNearMonth() ?: return emptyList()
        val today = timeProvider.today()
        return when (interval) {
            "1d" -> client.fetchDaily(near.iscd, today.minusDays(count.toLong() * 2 + 10), today)?.candles ?: emptyList()
            "1m" -> recentMinutes(near.iscd)
            else -> emptyList()
        }
    }

    /** 분봉 — 오늘부터 뒤로 밀며 데이터 있는 최근 영업일 하루치(주말·휴장·개장전 대응). */
    private fun recentMinutes(iscd: String): List<KisFuturesClient.FuturesBar> {
        val today = timeProvider.today()
        val nowTime = timeProvider.now().toLocalTime()
        var day = today
        repeat(5) {
            val end = if (day == today) nowTime else SESSION_END
            val bars = minutesOfDay(iscd, day, end)
            if (bars.isNotEmpty()) return bars
            day = day.minusDays(1)
        }
        return emptyList()
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
    val spotChangeRate: Double, // 현물 등락률(%)
    val basis: Double, // 선물 − 현물
    val dprt: Double, // 괴리율(%)
    val openInterest: Long, // 미결제약정(계약)
    val openInterestChange: Long, // 전일 대비 증감
    val rmnnDays: Int, // 만기 잔존일수
    val expiryDate: String, // 만기일 yyyy-MM-dd (근월물 최종거래일)
)
