package at.backend.leadingstock.domain

/** 순매수 흐름 전환 — 어느 방향으로 전환했는지([to])와 되돌리기 직전의 정점 누적([extremeEok], 억원·부호 포함). */
data class FlowTurn(val to: NetTradeSide, val extremeEok: Long)

/**
 * 한 (시장·투자자)의 누적 순매수 흐름 전환 감지.
 *
 * 진행 방향의 정점(extreme, 부호 포함 최대 누적)을 기억하고, 정점 대비 [reversalEok] 이상
 * 반대로 되돌리면 전환으로 본다. (예: 외인 순매도 정점 -6.8조 → -6.5조면 0.3조 매수 유입 = 전환 기미)
 * 전환 후엔 반대 방향 정점을 새로 추적하므로 다시 꺾이면 재전환을 잡는다.
 */
class InvestorFlowState private constructor(
    private val side: NetTradeSide?,
    private val extremeEok: Long,
) {
    /** 새 누적 [netEok](억원, 부호 포함)으로 전환 여부와 다음 상태를 돌려준다. */
    fun advance(netEok: Long, reversalEok: Long): Pair<FlowTurn?, InvestorFlowState> {
        val curSide = when {
            netEok > 0 -> NetTradeSide.BUY
            netEok < 0 -> NetTradeSide.SELL
            else -> null
        }
        // 방향이 아직 없으면 잡힐 때까지 정점만 따라간다.
        if (side == null) return null to InvestorFlowState(curSide, netEok)

        val dir = if (side == NetTradeSide.BUY) 1 else -1
        val curProg = netEok * dir // 추적 방향으로의 진행 정도(클수록 그 방향이 강함)
        val extProg = extremeEok * dir

        // 진행 방향으로 더 극단 → 정점 갱신, 전환 없음.
        if (curProg >= extProg) return null to InvestorFlowState(side, netEok)

        // 정점 대비 반대로 되돌린 양.
        val pullback = extProg - curProg
        if (pullback >= reversalEok) {
            val to = side.opposite()
            return FlowTurn(to, extremeEok) to InvestorFlowState(to, netEok) // 전환 — 정점 실어 보내고 반대 방향 정점 새로 시작
        }
        // 임계 미달 — 정점 유지, 전환 없음.
        return null to InvestorFlowState(side, extremeEok)
    }

    /** 재시작 복원·스냅샷 저장용 — 현재 진행 방향과 정점. side가 null이면 아직 추적 시작 전. */
    val currentSide: NetTradeSide? get() = side
    val currentExtremeEok: Long get() = extremeEok

    companion object {
        val INITIAL = InvestorFlowState(side = null, extremeEok = 0)

        /** DB 스냅샷에서 상태 복원. */
        fun restore(side: NetTradeSide, extremeEok: Long): InvestorFlowState =
            InvestorFlowState(side, extremeEok)
    }
}
