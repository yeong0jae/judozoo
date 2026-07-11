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

    /** 근월물 캔들 — interval "1d"(최근 [count]봉)/"1m"(당일). */
    fun candles(interval: String, count: Int): List<KisFuturesClient.FuturesBar> {
        val near = client.fetchNearMonth() ?: return emptyList()
        val today = timeProvider.today()
        return when (interval) {
            "1d" -> client.fetchDaily(near.iscd, today.minusDays(count.toLong() * 2 + 10), today)?.candles ?: emptyList()
            "1m" -> recentMinutes(near.iscd)
            else -> emptyList()
        }
    }

    /** 분봉 — 오늘부터 뒤로 밀며 데이터 있는 최근 영업일의 당일 분봉(주말·휴장·개장전 대응). */
    private fun recentMinutes(iscd: String): List<KisFuturesClient.FuturesBar> {
        val today = timeProvider.today()
        val nowTime = timeProvider.now().toLocalTime()
        var day = today
        repeat(5) {
            val hour = if (day == today) nowTime else LocalTime.of(15, 45)
            val bars = client.fetchMinute(iscd, day, hour)
            if (bars.isNotEmpty()) return bars
            day = day.minusDays(1)
        }
        return emptyList()
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
