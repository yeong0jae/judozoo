package judozoo.closingbet.domain

import judozoo.leadingstock.domain.MinuteCandle
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object SellWindow {
    val LENGTH: Duration = Duration.ofMinutes(5)
    private val NXT_START = LocalTime.of(8, 0)
    private val KRX_START = LocalTime.of(9, 0)

    data class SellPrice(val price: Double, val windowStart: LocalDateTime?)

    fun start(nxtListed: Boolean): LocalTime = if (nxtListed) NXT_START else KRX_START

    fun revealAt(sellDay: LocalDate, nxtListed: Boolean): LocalDateTime = sellDay.atTime(start(nxtListed)).plus(LENGTH)

    fun price(bars: List<MinuteCandle>, sellDay: LocalDate, nxtListed: Boolean, buyPrice: Long): SellPrice {
        val start = sellDay.atTime(start(nxtListed))
        val traded = bars.filter { !it.dateTime.isBefore(start) && it.dateTime.toLocalDate() == sellDay && it.volume > 0 }.sortedBy { it.dateTime }
        if (traded.isEmpty()) return SellPrice(buyPrice.toDouble(), null)
        val first = traded.first().dateTime
        val windowStart = if (first.isBefore(start.plus(LENGTH))) start else first
        val window = traded.filter { !it.dateTime.isBefore(windowStart) && it.dateTime.isBefore(windowStart.plus(LENGTH)) }
        return SellPrice((window.maxOf { it.highPrice } + window.minOf { it.lowPrice }) / 2.0, windowStart)
    }
}
