package judozoo.library

import kotlin.math.pow

/** 두 축을 함께 보는 순위 계산. 어느 도메인에도 매이지 않는다 — 무엇이 두 축인지는 부르는 쪽이 정한다. */
object Ranking {
    /**
     * 두 축 **백분위의 (가중) 기하평균**이 높은 순으로 [count]개.
     *
     * 값의 크기가 아니라 "이 무리에서 몇 등인가"만 본다. 한 축에 극단값이 하나 끼면 크기를 그대로 쓰는 방식은
     * 그 하나가 축 전체를 눌러 나머지를 구분하지 못하게 되는데, 백분위는 그 영향을 받지 않는다.
     * 기하평균이라 한쪽이 낮으면 함께 깎인다 — **두 축이 모두 높은 것**만 위로 온다.
     *
     * [firstWeight]는 첫 축에 주는 무게다(0.5면 대등). 점수가 같으면 먼저 온 것이 앞선다.
     */
    fun <T> topBalanced(items: List<T>, first: (T) -> Double, second: (T) -> Double, count: Int, firstWeight: Double = 0.5): List<T> {
        if (items.isEmpty() || count <= 0) return emptyList()
        val firsts = percentiles(items, first)
        val seconds = percentiles(items, second)
        val scores = items.indices.map { firsts[it].pow(firstWeight) * seconds[it].pow(1 - firstWeight) }
        return items.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it }).take(count).map { items[it] }
    }

    /**
     * 큰 값이 1.0, 가장 작은 값이 1/n. 0을 주지 않는 건 기하평균이 통째로 0이 되지 않게 하려는 것.
     * 동점이면 먼저 온 항목이 앞선다 — 같은 입력이면 같은 순서가 나와야 한다.
     */
    private fun <T> percentiles(items: List<T>, key: (T) -> Double): DoubleArray {
        val n = items.size
        val result = DoubleArray(n)
        items.indices.sortedWith(compareByDescending<Int> { key(items[it]) }.thenBy { it })
            .forEachIndexed { place, i -> result[i] = (n - place).toDouble() / n }
        return result
    }
}
