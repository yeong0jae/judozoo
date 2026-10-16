package judozoo.insight.domain

import java.time.Duration
import java.time.LocalDateTime

/**
 * Vertex가 아플 때 종목마다 타임아웃까지 매달리지 않게 한다. 시각은 부르는 쪽이 준다.
 *
 * 외부 일시 장애만 센다. 로컬 풀 포화는 상태를 유지하고, 답이 이상한 건 Vertex가 답한 것이라 성공으로 본다.
 * 클라이언트 오류는 다시 해도 같으니 사람이 고쳐 재배포할 때까지(새 프로세스가 뜰 때까지) 연다.
 */
class Circuit {
    enum class State(val gauge: Int) {
        CLOSED(0),

        /** 한 종목만 시험한다 */
        HALF_OPEN(1),

        /** Vertex를 부르지 않는다. 고른 종목은 행 없이 건너뛴다 — 닫힌 뒤 다시 골라진다 */
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
        /** 일시 장애가 이만큼 이어지면 Vertex를 쉰다 */
        const val THRESHOLD = 3

        /** 쉬는 시간. 지나면 한 종목으로 시험한다 */
        val COOLDOWN: Duration = Duration.ofMinutes(5)
    }
}
