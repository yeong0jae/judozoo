package judozoo.overseasleadingstock.application

import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 해외 주도주 폴러 — 후보 풀 갱신(15초) / 분봉 마감 확정(뉴욕 20:01).
 *
 * 장중에는 세 거래소 거래대금 순위를 늘 채워 둔다. 화면이 KIS 응답(거래소 3번)을 기다리지 않게 하려는 것이다.
 *
 * **후보 풀 갱신은 Python에서도 돈다**(028) — 타임라인·캘린더·왜 오르나가 Python의 풀을 읽는다.
 * **분봉 마감 확정은 여기서만** 돈다 — 분봉을 쓰는 화면이 모두 여기로 왔다. Python 쪽 등록은 같은 커밋에서 껐다.
 */
@Component
class OverseasLeadingStockScheduler(
    private val service: OverseasLeadingStockService,
    private val calendar: MarketCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 실패해도 기존 값은 그대로 쓰인다. 다음 회차가 다시 시도한다. */
    @Scheduled(fixedDelay = 15_000)
    fun refreshTradingValuePool() {
        val (holiday, tradingHours) = calendar.usMarketStatus()
        if (holiday || !tradingHours) return
        try {
            service.refreshTradingValuePool()
        } catch (e: Exception) {
            log.warn("해외 후보 풀 갱신 실패", e)
        }
    }

    /**
     * 애프터마켓(뉴욕 20:00)이 끝나고 1분 뒤 들고 있는 종목의 오늘 봉을 굳혀 DB에 넘긴다.
     * 뉴욕 시각으로 건다 — 서머타임에 따라 한국 시각으로는 09:01·10:01로 바뀐다.
     */
    @Scheduled(cron = "0 1 20 * * MON-FRI", zone = "America/New_York")
    fun settleMinutes() {
        if (calendar.isHoliday(Region.US)) return
        try {
            val failures = service.settleMinutes()
            if (failures > 0) log.warn("해외 분봉 마감 확정 — {}종목 실패", failures)
        } catch (e: Exception) {
            log.warn("해외 분봉 마감 확정 실패", e)
        }
    }
}
