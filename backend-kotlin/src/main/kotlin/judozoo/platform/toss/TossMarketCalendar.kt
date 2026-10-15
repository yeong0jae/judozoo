package judozoo.platform.toss

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate

/**
 * 토스 장 운영 정보 — 국내(KR)·해외(US) 시장 캘린더.
 *
 * 응답은 전일/당일/익일 3영업일을 준다. 개장 여부만 필요하므로 당일 세션 유무로 판정한다.
 * - KR: `today.integrated` 아래 세션이 있으면 개장
 * - US: `today`의 4세션(day/pre/regular/after)이 전부 비면 휴장
 */
@Component
class TossMarketCalendar(private val client: TossClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [region] "KR"/"US". 개장일이면 true, 휴장이면 false, 조회 실패면 null — 휴장으로 단정하지 않는다. */
    fun isTradingDay(region: String, on: LocalDate): Boolean? = try {
        val today = client.get("/api/v1/market-calendar/$region", mapOf("date" to on.toString()))["result"]?.get("today")
        if (today == null || today.isNull || today.isEmpty) null else isOpen(today)
    } catch (e: Exception) {
        log.error("토스 장 운영 정보 조회 실패 region={} date={}", region, on, e)
        null
    }

    /** KR은 통합 세션, US는 4세션 중 하나라도 시작시각이 있으면 개장. */
    private fun isOpen(day: JsonNode): Boolean {
        fun started(node: JsonNode?, session: String) = node?.get(session)?.get("startTime")?.asString()?.isNotEmpty() == true
        val integrated = day["integrated"]
        return KR_SESSIONS.any { started(integrated, it) } || US_SESSIONS.any { started(day, it) }
    }

    companion object {
        private val US_SESSIONS = listOf("dayMarket", "preMarket", "regularMarket", "afterMarket")
        private val KR_SESSIONS = listOf("regularMarket", "preMarket", "afterMarket")
    }
}
