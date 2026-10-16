package judozoo.leadingstock.domain

/**
 * 한 (시장·투자자)의 누적 순매수 흐름 전환 감지.
 *
 * 진행 방향의 정점을 기억하고, 정점 대비 임계 이상 반대로 되돌리면 전환으로 본다.
 * (예: 외인 순매도 정점 −6.8조 → −6.5조면 0.3조 매수 유입 = 전환 기미)
 * 전환 후엔 반대 방향 정점을 새로 추적하므로 다시 꺾이면 재전환을 잡는다.
 */
data class InvestorFlowState(val side: NetTradeSide? = null, val extremeEok: Long = 0) {

    /** 흐름 전환 — 전환 방향과 되돌리기 직전의 정점 누적(억원, 부호 포함). */
    data class FlowTurn(val to: NetTradeSide, val extremeEok: Long)

    fun advance(netEok: Long, reversalEok: Long): Pair<FlowTurn?, InvestorFlowState> {
        // 방향이 아직 없으면 잡힐 때까지 정점만 따라간다
        val side = side ?: return null to InvestorFlowState(NetTradeSide.of(netEok), netEok)
        val direction = if (side == NetTradeSide.BUY) 1 else -1
        val current = netEok * direction // 추적 방향으로의 진행 정도
        val extreme = extremeEok * direction
        return when {
            // 진행 방향으로 더 극단 → 정점 갱신, 전환 없음
            current >= extreme -> null to InvestorFlowState(side, netEok)
            // 전환 — 정점을 실어 보내고 반대 방향 정점을 새로 시작한다
            extreme - current >= reversalEok -> FlowTurn(side.opposite(), extremeEok) to InvestorFlowState(side.opposite(), netEok)
            // 임계 미달 — 정점 유지
            else -> null to this
        }
    }
}
