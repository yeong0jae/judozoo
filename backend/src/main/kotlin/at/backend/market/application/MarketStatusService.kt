package at.backend.market.application

import at.backend.library.time.TimeProvider
import org.springframework.stereotype.Service
import java.time.LocalTime

@Service
class MarketStatusService(
    private val timeProvider: TimeProvider,
    private val marketCalendarService: MarketCalendarService,
) {

    fun getStatus(): MarketStatusResult {
        val now = timeProvider.now().toLocalTime()
        // 국내 휴장(주말·공휴일)은 토스 장 운영 정보로 판정(주말 폴백 포함).
        val isHoliday = marketCalendarService.isHoliday(MarketCalendarService.Region.KR)
        val tradingHoursOpen = now in TRADING_START..TRADING_END
        return MarketStatusResult(
            isHoliday = isHoliday,
            tradingHoursOpen = tradingHoursOpen,
        )
    }

    data class MarketStatusResult(
        val isHoliday: Boolean,
        val tradingHoursOpen: Boolean,
    )

    companion object {
        private val TRADING_START = LocalTime.of(8, 0)       // NXT 프리마켓(08:00~08:50) 포함
        private val TRADING_END = LocalTime.of(20, 0)        // KRX 마감 후 NXT 애프터마켓(~20:00) 포함
    }
}
