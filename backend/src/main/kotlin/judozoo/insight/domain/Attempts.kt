package judozoo.insight.domain

import java.time.Duration
import java.time.LocalDateTime

data class Attempt(val at: LocalDateTime, val published: Boolean, val explained: Boolean = false, val failure: Failure? = null)

class Attempts(private val items: List<Attempt> = emptyList()) {
    fun isEmpty() = items.isEmpty()

    private val last: Attempt? get() = items.maxByOrNull { it.at }

    fun madeWithin(now: LocalDateTime, span: Duration): Boolean = last?.let { Duration.between(it.at, now) < span } ?: false

    val explained: Boolean get() = items.any { it.published && it.explained }

    fun needsRetry(now: LocalDateTime): Boolean {
        val streak = items.sortedByDescending { it.at }.takeWhile { it.failure?.transient == true }.size
        if (streak !in 1..RETRY_AFTER.size) return false
        return Duration.between(last!!.at, now) >= RETRY_AFTER[streak - 1]
    }

    companion object {
        val RETRY_AFTER: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15))
    }
}
