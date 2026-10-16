package judozoo.leadingstock.domain

import kotlin.math.absoluteValue

/**
 * 한 (시장·투자자)의 누적 순매수 단계 상태.
 *
 * 단계를 새로 넘나드는 **순간에만** 한 번씩 전이를 잡는다.
 * 올라갈 때는 단계선에서 승급, 내려올 때는 `단계선 − 완충` 아래로 떨어져야 강등한다.
 * 방향이 바뀌면(0 통과 포함) 단계를 리셋하고 반대편부터 다시 센다.
 */
data class InvestorNetBuyState(val side: NetTradeSide? = null, val level: Int = 0) {

    /** 레벨 전이 한 건 — 어느 방향으로 몇 단계에 도달했는지. */
    data class NetBuyTransition(val side: NetTradeSide, val level: Int)

    fun advance(netEok: Long, stepEok: Long, bufferEok: Long): Pair<NetBuyTransition?, InvestorNetBuyState> {
        val newSide = NetTradeSide.of(netEok)
        // 방향이 그대로면 현재 단계에서 이어 판정, 바뀌면 0부터 다시
        val base = if (newSide == side) level else 0
        val newLevel = if (newSide == null) 0 else stepWithHysteresis(base, netEok.absoluteValue, stepEok, bufferEok)
        val transition = if (newSide != null && newLevel >= 1 && newLevel != base) NetBuyTransition(newSide, newLevel) else null
        return transition to InvestorNetBuyState(newSide, newLevel)
    }

    /** 승급은 단계선에서, 강등은 `단계선 − 완충`에서. */
    private fun stepWithHysteresis(current: Int, magnitude: Long, step: Long, buffer: Long): Int {
        var s = current
        while (magnitude >= (s + 1) * step) s++
        while (s > 0 && magnitude < s * step - buffer) s--
        return s
    }
}
