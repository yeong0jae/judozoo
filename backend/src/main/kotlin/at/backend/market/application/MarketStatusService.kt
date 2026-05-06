package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.trading.TradingProperties
import org.springframework.stereotype.Service
import java.time.LocalTime

@Service
class MarketStatusService(
    private val kisRestClient: KisRestClient,
    private val tradingProperties: TradingProperties,
    private val timeProvider: TimeProvider,
    private val marketDataStream: MarketDataStream,
) {

    fun getStatus(): MarketStatusResult {
        val now = timeProvider.now().toLocalTime()
        val isHoliday = kisRestClient.checkHoliday(timeProvider.today()).output.firstOrNull()?.opndYn != "Y"
        val tradingHoursOpen = now in TRADING_START..TRADING_END
        val cutoffPassed = now > CUTOFF_BASE.minusMinutes((tradingProperties.defaultBuyIntervalMin * 2).toLong())
        return MarketStatusResult(
            marketMode = marketDataStream.currentMode().name,
            isHoliday = isHoliday,
            tradingHoursOpen = tradingHoursOpen,
            cutoffPassed = cutoffPassed,
        )
    }

    data class MarketStatusResult(
        val marketMode: String,
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
