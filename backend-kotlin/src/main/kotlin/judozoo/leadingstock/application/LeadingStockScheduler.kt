package judozoo.leadingstock.application

import judozoo.market.application.MarketCalendar
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 국내 주도주 폴러 — 후보 풀 갱신(15초) / 감시 풀 당일 분봉 갱신(20초).
 *
 * 장중에는 거래대금 상위와 감시 풀의 오늘 분봉을 늘 채워 둔다 — 화면이 키움·토스 응답을 기다리지 않게.
 *
 * **둘 다 Python에서도 돈다**(028) — 메모리 상태라 Python의 시그널·타임라인·종가베팅이 자기 풀을 읽는다.
 * **분봉 마감 확정(20:01, DB에 쓴다)은 Python만** 돈다 — 여기서는 확정본을 DB에서 읽는다.
 */
@Component
class LeadingStockScheduler(private val service: LeadingStockService, private val calendar: MarketCalendar) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 실패해도 기존 값은 그대로 쓰인다. 다음 회차가 다시 시도한다. */
    @Scheduled(fixedDelay = 15_000)
    fun refreshTradingValuePool() {
        if (!inSession()) return
        try {
            service.refreshTradingValuePool()
        } catch (e: Exception) {
            log.warn("거래대금 상위 갱신 실패", e)
        }
    }

    /** 종목 하나가 실패하면 그 종목만 기존 봉을 쓴다. */
    @Scheduled(fixedDelay = 20_000)
    fun syncTodayMinutes() {
        if (!inSession()) return
        try {
            val failures = service.syncTodayMinutes()
            if (failures > 0) log.warn("당일 분봉 갱신 — {}종목 실패", failures)
        } catch (e: Exception) {
            log.warn("당일 분봉 갱신 실패", e)
        }
    }

    private fun inSession(): Boolean {
        val (holiday, tradingHours) = calendar.marketStatus()
        return !holiday && tradingHours
    }
}
