package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisFuturesClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 코스피 야간선물(18:00~익일 06:00) — KIS 시장구분 CM. 다음날 시초가 갭을 가늠하는 용도.
 * 근월물 코드는 정규장 전광판에서 뽑은 것을 그대로 쓴다(전광판은 야간을 지원하지 않는다).
 * KIS는 한 세션을 하나의 영업일로 묶으려고 자정 이후 시각을 +24시간으로 보낸다(클라이언트에서 되돌림).
 */
@Service
class MarketNightFuturesService(
    private val client: KisFuturesClient,
    private val timeProvider: TimeProvider,
) {

    /** 야간선물 시세 — 현재가·갭(직전 정규장 종가 대비)·미결제. 데이터 없으면 null. */
    @Cacheable("nightFuturesQuote", unless = "#result == null")
    fun quote(): NightFuturesQuote? {
        val near = client.fetchNearMonth() ?: return null
        val p = client.fetchPrice(near.iscd, KisFuturesClient.NIGHT) ?: return null
        return NightFuturesQuote(
            price = p.price,
            changeRate = p.changeRate,
            dayClose = p.prevClose, // 직전 정규장 종가(전일 대비로 역산)
            gap = p.priceChange,
            open = p.open,
            high = p.high,
            low = p.low,
            volume = p.volume,
            openInterest = p.openInterest,
            openInterestChange = p.openInterestChange,
        )
    }

    /** 야간선물 캔들 — interval "1d"(최근 [count]봉)/"1m"(최근 세션 하나). */
    @Cacheable("nightFuturesCandles", key = "#interval + ':' + #count", unless = "#result.isEmpty()")
    fun candles(interval: String, count: Int): List<KisFuturesClient.FuturesBar> {
        val near = client.fetchNearMonth() ?: return emptyList()
        val today = timeProvider.today()
        return when (interval) {
            "1d" -> client.fetchDaily(
                near.iscd,
                today.minusDays(count.toLong() * 2 + 10),
                today,
                KisFuturesClient.NIGHT,
            )?.candles ?: emptyList()
            "1m" -> recentSession(near.iscd)
            else -> emptyList()
        }
    }

    /**
     * 최근 야간 세션 하나의 분봉 — 오늘 기준일부터 뒤로 밀며 데이터 있는 세션을 찾는다.
     * 세션 기준일은 18:00이 속한 날이라, 새벽(06:00 이전)에는 아직 어제 세션이 진행 중이다.
     */
    private fun recentSession(iscd: String): List<KisFuturesClient.FuturesBar> {
        var day = timeProvider.today()
        if (timeProvider.now().toLocalTime().hour < SESSION_END_HOUR) day = day.minusDays(1) // 새벽 = 어제 시작한 세션
        repeat(5) {
            val bars = sessionMinutes(iscd, day)
            if (bars.isNotEmpty()) return bars
            day = day.minusDays(1)
        }
        return emptyList()
    }

    /** [day] 18:00에 시작한 세션의 분봉 — 102봉씩 뒤로 페이징. 시각은 18:00~29:59(=익일 05:59). */
    private fun sessionMinutes(iscd: String, day: LocalDate): List<KisFuturesClient.FuturesBar> {
        val bars = sortedMapOf<String, KisFuturesClient.FuturesBar>()
        var minute = SESSION_END // 29:59부터 역순
        repeat(MINUTE_PAGES) {
            val page = client.fetchMinute(iscd, day, hhmmss(minute), KisFuturesClient.NIGHT)
            if (page.isEmpty()) return bars.values.toList()
            page.forEach { bars["${it.date} ${it.time}"] = it }
            val earliest = extendedMinute(page.first())
            if (earliest <= SESSION_START) return bars.values.toList()
            minute = earliest - 1
        }
        return bars.values.toList()
    }

    /** 봉의 시각을 세션 기준(자정 넘김 = +24시간)의 '분'으로 되돌린다 — 페이징 커서 계산용. */
    private fun extendedMinute(bar: KisFuturesClient.FuturesBar): Int {
        val (h, m) = bar.time.split(":").let { it[0].toInt() to it[1].toInt() }
        val hour = if (h < SESSION_END_HOUR) h + 24 else h // 00~05시는 익일 = +24
        return hour * 60 + m
    }

    /** 분 단위 시각을 KIS가 요구하는 HHMMSS로 — 24시 이상도 그대로 보낸다(예: 1530분 → "253000"). */
    private fun hhmmss(minute: Int) = "%02d%02d00".format(minute / 60, minute % 60)

    companion object {
        private const val SESSION_END_HOUR = 6 // 야간 마감 06:00 — 이 시각 전이면 아직 어제 시작한 세션
        private const val SESSION_START = 18 * 60 // 18:00
        private const val SESSION_END = 29 * 60 + 59 // 29:59 = 익일 05:59
        private const val MINUTE_PAGES = 8 // 세션 720분을 102봉씩 덮는 최대 페이지 수
    }
}

/** 코스피 야간선물 시세. 값은 지수 포인트. */
data class NightFuturesQuote(
    val price: Double,
    val changeRate: Double, // 직전 정규장 종가 대비 등락률(%)
    val dayClose: Double, // 직전 정규장 종가
    val gap: Double, // 정규장 종가 대비 갭(포인트) — 다음날 시초가 가늠용
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Long,
    val openInterest: Long,
    val openInterestChange: Long,
)
