package judozoo.closingbet.domain

import judozoo.leadingstock.domain.MinuteCandle
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 다음 날 아침 5분에 판다. NXT 상장이면 08:00, 아니면 09:00부터. */
object SellWindow {
    val LENGTH: Duration = Duration.ofMinutes(5)
    private val NXT_START = LocalTime.of(8, 0)
    private val KRX_START = LocalTime.of(9, 0)

    /** [windowStart]는 실제로 값을 낸 5분의 시작 — 창에 체결이 없으면 미뤄진 시각, 끝까지 없으면 null. */
    data class SellPrice(val price: Double, val windowStart: LocalDateTime?)

    fun start(nxtListed: Boolean): LocalTime = if (nxtListed) NXT_START else KRX_START

    /** 결과를 공개하는 시각 — 매도 창이 끝나는 순간. */
    fun revealAt(sellDay: LocalDate, nxtListed: Boolean): LocalDateTime = sellDay.atTime(start(nxtListed)).plus(LENGTH)

    /**
     * 다음 날 아침 5분봉의 (고가 + 저가) ÷ 2.
     *
     * 창에 체결이 없으면 그날 첫 체결부터 5분으로 미룬다. 그날 끝까지 없으면(거래정지) 매수가 — 0%.
     * 분봉 시각은 그 1분의 시작이다(08:00 봉 = 08:00~08:01).
     */
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
