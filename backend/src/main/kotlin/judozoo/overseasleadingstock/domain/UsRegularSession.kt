package judozoo.overseasleadingstock.domain

import judozoo.library.KST
import judozoo.market.domain.Region
import java.time.LocalTime

object UsRegularSession {
    val OPEN: LocalTime = LocalTime.of(9, 30)

    fun openPrice(candles: List<OverseasMinuteCandle>): Double? {
        val day = candles.maxOfOrNull { it.tradingDay } ?: return null
        return candles.filter { it.tradingDay == day && it.open > 0 && !newYorkTime(it).isBefore(OPEN) }.minByOrNull { it.dateTime }?.open
    }

    private fun newYorkTime(candle: OverseasMinuteCandle): LocalTime =
        candle.dateTime.atZone(KST).withZoneSameInstant(Region.US.zone).toLocalTime()
}
