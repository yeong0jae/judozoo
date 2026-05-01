package at.backend.trading.domain.cycle

sealed class CycleState {

    data object Initiated : CycleState()
    data class Buying(val nextAttempt: Int) : CycleState() {
        init {
            require(nextAttempt in 1..3) { "매수 회차는 1~3이어야 합니다: $nextAttempt" }
        }
    }
    data object Holding : CycleState()
    data class Liquidating(val reason: CloseReason) : CycleState()
    data class Closed(val reason: CloseReason) : CycleState()

    fun canTransitionTo(next: CycleState): Boolean = when (this) {
        is Initiated -> next is Buying && next.nextAttempt == 1

        is Buying -> when (next) {
            is Buying -> next.nextAttempt == this.nextAttempt + 1 && next.nextAttempt <= 3
            is Holding -> true
            is Liquidating -> true
            is Closed -> next.reason == CloseReason.NO_FILL || next.reason == CloseReason.CANCELLED
            is Initiated -> false
        }

        is Holding -> next is Holding || next is Liquidating

        is Liquidating -> next is Closed

        is Closed -> false
    }
}
