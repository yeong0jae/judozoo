package judozoo.platform.toss

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate

@Component
class TossMarketCalendar(private val client: TossClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isTradingDay(region: String, on: LocalDate): Boolean? = try {
        val today = client.get("/api/v1/market-calendar/$region", mapOf("date" to on.toString()))["result"]?.get("today")
        if (today == null || today.isNull || today.isEmpty) null else isOpen(today)
    } catch (e: Exception) {
        log.error("토스 장 운영 정보 조회 실패 region={} date={}", region, on, e)
        null
    }

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
