package judozoo.closingbet.domain

data class MarketFlow(val foreign: FlowLine, val institution: FlowLine) {
    val late: Long get() = foreign.late + institution.late
}
