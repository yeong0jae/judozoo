package judozoo.insight.domain

import java.time.Duration
import java.time.LocalDateTime

/** 한 번 만든 기록 — 언제, 게시됐는가, 설명됐는가, 왜 실패했는가만 판단에 쓴다. [at]은 현지 시각. */
data class Attempt(val at: LocalDateTime, val published: Boolean, val explained: Boolean = false, val failure: Failure? = null)

/** 한 종목의 오늘 생성 기록. */
class Attempts(private val items: List<Attempt> = emptyList()) {
    fun isEmpty() = items.isEmpty()

    private val last: Attempt? get() = items.maxByOrNull { it.at }

    fun madeWithin(now: LocalDateTime, span: Duration): Boolean = last?.let { Duration.between(it.at, now) < span } ?: false

    /** 게시된 사유 중 하나라도 설명됐는가 — 그러면 화면엔 그 사유가 남는다([Shown]). */
    val explained: Boolean get() = items.any { it.published && it.explained }

    /** 끝에서부터 이어진 일시 장애 실패가 n번이고, 마지막 실패 뒤 `RETRY_AFTER[n-1]`이 지났으면. */
    fun needsRetry(now: LocalDateTime): Boolean {
        val streak = items.sortedByDescending { it.at }.takeWhile { it.failure?.transient == true }.size
        if (streak !in 1..RETRY_AFTER.size) return false
        return Duration.between(last!!.at, now) >= RETRY_AFTER[streak - 1]
    }

    companion object {
        /** 일시 장애로 실패한 종목의 재시도 대기. n번 이어 실패하면 n번째 값, 세 번 다 실패하면 정해진 시각에 맡긴다 */
        val RETRY_AFTER: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15))
    }
}
