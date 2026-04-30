package at.backend.trading.domain.cycle

import at.backend.trading.domain.CloseReason

sealed class CycleState {
    data object Initiated : CycleState()
    data class Buying(val nextAttempt: Int) : CycleState()
    data object Monitoring : CycleState()
    data class Liquidating(val reason: CloseReason) : CycleState()
    data class Closed(val reason: CloseReason) : CycleState()

    fun canTransitionTo(next: CycleState): Boolean = when (this) {
        is Initiated -> next is Buying && next.nextAttempt == 1
        is Buying -> when (next) {
            is Buying -> next.nextAttempt == this.nextAttempt + 1 && next.nextAttempt <= 3
            is Monitoring -> true
            is Liquidating -> true
            is Closed -> next.reason == CloseReason.NO_FILL || next.reason == CloseReason.CANCELLED
            is Initiated -> false
        }
        is Monitoring -> next is Monitoring || next is Liquidating
        is Liquidating -> next is Closed
        is Closed -> false
    }
}
