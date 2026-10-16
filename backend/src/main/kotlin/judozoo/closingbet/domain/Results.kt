package judozoo.closingbet.domain

import java.time.LocalDateTime

data class Result(val userId: Long, val stockCode: String, val placedAt: LocalDateTime, val outcome: Outcome)

data class Ranked(val rank: Int, val result: Result)

class Results(private val results: List<Result>) {
    val size: Int get() = results.size

    fun ranked(byRate: Boolean): List<Ranked> {
        fun value(r: Result): Double = if (byRate) r.outcome.rate else r.outcome.pnl.toDouble()
        val ranked = mutableListOf<Ranked>()
        results.sortedWith(compareByDescending<Result> { value(it) }.thenBy { it.placedAt }).forEachIndexed { i, r ->
            val same = ranked.isNotEmpty() && value(ranked.last().result) == value(r)
            ranked += Ranked(if (same) ranked.last().rank else i + 1, r)
        }
        return ranked
    }

    fun top(byRate: Boolean, n: Int = 5): List<Ranked> = ranked(byRate).take(n)

    fun rankOf(userId: Long, byRate: Boolean): Ranked? = ranked(byRate).firstOrNull { it.result.userId == userId }
}
