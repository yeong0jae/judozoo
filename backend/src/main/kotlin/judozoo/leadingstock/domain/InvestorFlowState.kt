package judozoo.leadingstock.domain

data class InvestorFlowState(val side: NetTradeSide? = null, val extremeEok: Long = 0) {
    data class FlowTurn(val to: NetTradeSide, val extremeEok: Long)

    fun advance(netEok: Long, reversalEok: Long): Pair<FlowTurn?, InvestorFlowState> {
        val side = side ?: return null to InvestorFlowState(NetTradeSide.of(netEok), netEok)
        val direction = if (side == NetTradeSide.BUY) 1 else -1
        val current = netEok * direction
        val extreme = extremeEok * direction
        return when {
            current >= extreme -> null to InvestorFlowState(side, netEok)
            extreme - current >= reversalEok -> FlowTurn(side.opposite(), extremeEok) to InvestorFlowState(side.opposite(), netEok)
            else -> null to this
        }
    }
}
