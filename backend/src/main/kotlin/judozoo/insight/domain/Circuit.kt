package judozoo.insight.domain

import java.time.Duration
import java.time.LocalDateTime

class Circuit {
    enum class State(val gauge: Int) {
        CLOSED(0),

        HALF_OPEN(1),

        OPEN(2),
    }

    internal var failures = 0
        private set
    internal var openUntil: LocalDateTime? = null
        private set
    private var stuck = false

    fun state(now: LocalDateTime): State = when {
        stuck -> State.OPEN
        openUntil == null -> State.CLOSED
        now.isBefore(openUntil) -> State.OPEN
        else -> State.HALF_OPEN
    }

    fun record(failure: Failure?, now: LocalDateTime) {
        when {
            failure == Failure.POOL_TIMEOUT -> return
            failure == Failure.CLIENT_ERROR -> stuck = true
            failure == null || !failure.transient -> {
                failures = 0
                openUntil = null
            }
            else -> {
                val trial = state(now) == State.HALF_OPEN
                failures++
                if (trial || failures >= THRESHOLD) {
                    openUntil = now.plus(COOLDOWN)
                    failures = 0
                }
            }
        }
    }

    companion object {
        const val THRESHOLD = 3

        val COOLDOWN: Duration = Duration.ofMinutes(5)
    }
}
