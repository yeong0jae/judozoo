package at.backend.platform.toss.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * 토스 장 운영 정보 — 국내(KR)·해외(US) 시장 캘린더. GET /api/v1/market-calendar/{region}.
 * 응답은 전일/당일/익일 3영업일 정보를 준다. 개장 여부만 필요하므로 당일(today) 세션 유무로 판정한다.
 *   - KR: today.integrated 아래 정규장 세션이 있으면 개장.
 *   - US: today의 4세션(dayMarket/preMarket/regularMarket/afterMarket)이 전부 null이면 휴장.
 * 모든 시간은 KST(+09:00). date는 해당 시장의 현지 날짜 기준.
 */
@Component
class TossMarketCalendarClient(
    private val tossRestClient: RestClient,
    private val authClient: TossAuthClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [region] "KR"/"US". [date] 조회 기준일(현지). 개장일이면 true, 휴장이면 false, 조회 실패면 null. */
    fun isTradingDay(region: String, date: LocalDate): Boolean? {
        return try {
            val token = authClient.getAccessToken()
            val response = tossRestClient.get()
                .uri { b ->
                    b.path("/api/v1/market-calendar/{region}")
                        .queryParam("date", date.toString())
                        .build(region)
                }
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(CalendarResponse::class.java)
                ?: return null
            response.result?.today?.isOpen()
        } catch (e: Exception) {
            log.error("토스 장 운영 정보 조회 실패 (region={}, date={})", region, date, e)
            null
        }
    }

    // ── 응답 DTO ──────────────────────────────────────────────
    data class CalendarResponse(val result: Result? = null) {
        data class Result(val today: Day? = null)
    }

    /** KR은 integrated에 세션이 중첩, US는 4세션이 최상위에 평평하게 온다. 무관한 쪽은 null. */
    data class Day(
        val date: String? = null,
        val integrated: Integrated? = null, // KR
        val dayMarket: Session? = null,     // US
        val preMarket: Session? = null,     // US
        val regularMarket: Session? = null, // US
        val afterMarket: Session? = null,   // US
    ) {
        /** 개장일이면 true — KR은 통합 정규장, US는 4세션 중 하나라도 시작시각이 있으면 개장. */
        fun isOpen(): Boolean =
            integrated?.hasSession() == true ||
                listOf(dayMarket, preMarket, regularMarket, afterMarket).any { it?.startTime != null }
    }

    data class Integrated(
        val preMarket: Session? = null,
        val regularMarket: Session? = null,
        val afterMarket: Session? = null,
    ) {
        fun hasSession(): Boolean =
            regularMarket?.startTime != null || preMarket?.startTime != null || afterMarket?.startTime != null
    }

    data class Session(val startTime: String? = null)
}
