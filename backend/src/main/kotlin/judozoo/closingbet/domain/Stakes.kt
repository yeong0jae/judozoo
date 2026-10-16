package judozoo.closingbet.domain

import java.time.LocalDateTime

/** 한 사람의 걸어 둔 베팅. */
data class Stake(val userId: Long, val stockCode: String, val amountMan: Int, val placedAt: LocalDateTime)

/** 한 판에 걸린 베팅들 — 판돈과 참여 수. */
class Stakes(private val stakes: List<Stake>) {
    val totalMan: Long get() = stakes.sumOf { it.amountMan.toLong() }

    val players: Int get() = stakes.map { it.userId }.toSet().size

    fun byStock(): Map<String, Long> = stakes.groupBy { it.stockCode }.mapValues { (_, s) -> s.sumOf { it.amountMan.toLong() } }

    fun crowd(): Map<String, Int> = stakes.groupingBy { it.stockCode }.eachCount()
}
