package at.backend.system.application

import at.backend.platform.kis.client.KisRestClient
import at.backend.trading.TradingProperties
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime

@Service
class SystemService(
    private val kisRestClient: KisRestClient,
    private val tradingProperties: TradingProperties,
) {

    fun getStatus(): SystemStatusResult {
        val now = LocalTime.now()
        val isHoliday = kisRestClient.checkHoliday(LocalDate.now()).output.firstOrNull()?.bzdyYn != "Y"
        val tradingHoursOpen = now >= TRADING_START && now <= TRADING_END
        val cutoffPassed = now > CUTOFF_BASE.minusMinutes((tradingProperties.defaultBuyIntervalMin * 2).toLong())
        return SystemStatusResult(
            marketMode = "WS",
            tokenStatus = "OK",
            isHoliday = isHoliday,
            tradingHoursOpen = tradingHoursOpen,
            cutoffPassed = cutoffPassed,
        )
    }

    data class SystemStatusResult(
        val marketMode: String,
        val tokenStatus: String,
        val isHoliday: Boolean,
        val tradingHoursOpen: Boolean,
        val cutoffPassed: Boolean,
    )

    companion object {
        private val TRADING_START = LocalTime.of(9, 0)
        private val TRADING_END = LocalTime.of(15, 30)
        private val CUTOFF_BASE = LocalTime.of(15, 20)
    }
}
