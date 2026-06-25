package at.backend.leadingstock.domain

import kotlin.math.abs

/** 레벨 전이 한 건 — 어느 방향으로 몇 단계에 도달했는지. */
data class NetBuyTransition(val side: NetTradeSide, val level: Int)

/**
 * 한 (시장·투자자) 계열의 누적 순매수 단계 상태.
 *
 * 누적 순매수액이 단계(조/천억)를 새로 넘나드는 **순간에만** 한 번씩 전이를 잡는다.
 * - 올라갈 때: 누적 크기가 다음 단계선(`n*step`)에 닿으면 그 단계로 승급.
 * - 내려올 때: `n*step - buffer` 아래로 떨어져야 한 단계 강등(경계 떨림은 완충이 흡수).
 * - 방향이 바뀌면(순매수↔순매도, 0 통과 포함) 단계를 리셋하고 반대편부터 다시 센다.
 *
 * 전이는 단계가 실제로 바뀌고 1단계 이상일 때만 보고한다(0으로 복귀=중립은 무신호).
 */
class InvestorNetBuyState private constructor(
    private val side: NetTradeSide?,
    private val level: Int,
) {
    /**
     * 새 누적 순매수액 [netEok](억원, 부호 포함)을 받아 이번에 발생한 전이와 다음 상태를 돌려준다.
     * [stepEok] 한 단계 크기(억원), [bufferEok] 경계 완충(억원).
     */
    fun advance(netEok: Long, stepEok: Long, bufferEok: Long): Pair<NetBuyTransition?, InvestorNetBuyState> {
        val newSide = when {
            netEok > 0 -> NetTradeSide.BUY
            netEok < 0 -> NetTradeSide.SELL
            else -> null
        }
        // 방향이 그대로면 현재 단계에서 이어 판정, 바뀌면 0부터 다시.
        val baseLevel = if (newSide == side) level else 0
        val newLevel = if (newSide == null) 0 else stepWithHysteresis(baseLevel, abs(netEok), stepEok, bufferEok)

        val transition =
            if (newSide != null && newLevel >= 1 && newLevel != baseLevel) NetBuyTransition(newSide, newLevel)
            else null
        return transition to InvestorNetBuyState(newSide, newLevel)
    }

    /** 완충을 둔 단계 산정 — 승급은 단계선에서, 강등은 단계선-완충에서. */
    private fun stepWithHysteresis(current: Int, magnitude: Long, step: Long, buffer: Long): Int {
        var s = current
        while (magnitude >= (s + 1) * step) s++
        while (s > 0 && magnitude < s * step - buffer) s--
        return s
    }

    companion object {
        val INITIAL = InvestorNetBuyState(side = null, level = 0)
    }
}
