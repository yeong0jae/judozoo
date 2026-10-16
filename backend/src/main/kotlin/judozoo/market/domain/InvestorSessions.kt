package judozoo.market.domain

import java.time.LocalDate

data class InvestorSessions<N>(val date: LocalDate, val sessions: List<Net<N>>) {
    data class Net<N>(val session: InvestorSession, val nets: N?, val delta: N? = null) {
        val name: String get() = session.label
    }

    fun of(session: InvestorSession): N? = sessions.firstOrNull { it.session == session }?.nets
}
