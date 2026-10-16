package judozoo.library

import kotlin.math.pow

object Ranking {
    fun <T> topBalanced(items: List<T>, first: (T) -> Double, second: (T) -> Double, count: Int, firstWeight: Double = 0.5): List<T> {
        if (items.isEmpty() || count <= 0) return emptyList()
        val firsts = percentiles(items, first)
        val seconds = percentiles(items, second)
        val scores = items.indices.map { firsts[it].pow(firstWeight) * seconds[it].pow(1 - firstWeight) }
        return items.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it }).take(count).map { items[it] }
    }

    private fun <T> percentiles(items: List<T>, key: (T) -> Double): DoubleArray {
        val n = items.size
        val result = DoubleArray(n)
        items.indices.sortedWith(compareByDescending<Int> { key(items[it]) }.thenBy { it })
            .forEachIndexed { place, i -> result[i] = (n - place).toDouble() / n }
        return result
    }
}
