package judozoo.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RankingTest {
    private data class Item(val name: String, val first: Double, val second: Double)

    private fun top(items: List<Item>, count: Int, firstWeight: Double = 0.5) =
        Ranking.topBalanced(items, { it.first }, { it.second }, count, firstWeight).map { it.name }

    @Nested
    inner class 두_축_균형_순위 {
        @Test
        fun `두 축이 모두 높은 것이 한쪽만 높은 것을 이긴다`() {
            val items = listOf(Item("둘다중상", 80.0, 80.0), Item("한쪽만최고", 100.0, 10.0), Item("반대쪽만최고", 10.0, 100.0))

            top(items, 1) shouldBe listOf("둘다중상")
        }

        /** 크기를 그대로 쓰면 '축2 = 1000'이 1위가 된다. 백분위는 등수만 보므로 한 칸 차이다. */
        @Test
        fun `한 축의 극단값이 순위를 끌고 가지 못한다`() {
            val items = listOf(Item("대장", 1000.0, 12.0), Item("극단값", 10.0, 1000.0), Item("둘다상위", 900.0, 15.0))

            top(items, 2) shouldBe listOf("둘다상위", "대장")
        }

        @Test
        fun `요청한 수보다 적으면 있는 만큼만 준다`() {
            top(listOf(Item("하나", 1.0, 1.0)), 5) shouldBe listOf("하나")
        }

        @Test
        fun `빈 목록은 빈 결과다`() {
            top(emptyList(), 3) shouldBe emptyList()
        }

        @Test
        fun `첫 축에 무게를 더 주면 그 축이 높은 쪽이 올라온다`() {
            val items = listOf(Item("축1우세", 100.0, 10.0), Item("축2우세", 10.0, 100.0))

            top(items, 1, 0.6) shouldBe listOf("축1우세")
            top(items, 1, 0.4) shouldBe listOf("축2우세")
        }

        /** 같은 입력이 같은 순서를 내야 새로고침마다 줄이 바뀌지 않는다. */
        @Test
        fun `점수가 같으면 먼저 온 것이 앞선다`() {
            top(listOf(Item("먼저", 10.0, 20.0), Item("나중", 20.0, 10.0)), 2) shouldBe listOf("먼저", "나중")
        }
    }
}
