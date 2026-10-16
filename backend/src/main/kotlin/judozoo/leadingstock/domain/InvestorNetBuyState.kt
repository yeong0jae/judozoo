package judozoo.leadingstock.domain

import kotlin.math.absoluteValue

data class InvestorNetBuyState(val side: NetTradeSide? = null, val level: Int = 0) {
    data class NetBuyTransition(val side: NetTradeSide, val level: Int)

    fun advance(netEok: Long, stepEok: Long, bufferEok: Long): Pair<NetBuyTransition?, InvestorNetBuyState> {
        val newSide = NetTradeSide.of(netEok)
        val base = if (newSide == side) level else 0
        val newLevel = if (newSide == null) 0 else stepWithHysteresis(base, netEok.absoluteValue, stepEok, bufferEok)
        val transition = if (newSide != null && newLevel >= 1 && newLevel != base) NetBuyTransition(newSide, newLevel) else null
        return transition to InvestorNetBuyState(newSide, newLevel)
    }

    private fun stepWithHysteresis(current: Int, magnitude: Long, step: Long, buffer: Long): Int {
        var s = current
        while (magnitude >= (s + 1) * step) s++
        while (s > 0 && magnitude < s * step - buffer) s--
        return s
    }
}
