package judozoo.closingbet.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import judozoo.closingbet.domain.PhaseTest.Companion.FRI
import judozoo.closingbet.domain.PhaseTest.Companion.THU
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StakesAndResultsTest {
    @Nested
    inner class 판돈 {
        @Test
        fun `참여 수와 종목별 판돈을 센다`() {
            val s = Stakes(listOf(Stake(1, "000660", 2_000, FRI.atTime(15, 0)), Stake(2, "000660", 500, FRI.atTime(16, 0)), Stake(3, "247540", 100, FRI.atTime(17, 0))))

            (s.totalMan to s.players) shouldBe (2_600L to 3)
            s.byStock() shouldBe mapOf("000660" to 2_500L, "247540" to 100L)
            s.crowd() shouldBe mapOf("000660" to 2, "247540" to 1)
        }
    }

    @Nested
    inner class 랭킹 {
        private fun result(user: Long, pnl: Long, rate: Double, minute: Int) = Result(user, "000660", THU.atTime(15, minute), Outcome(pnl, rate))

        @Test
        fun `같은 값이면 공동 순위이고 다음 순위는 건너뛴다`() {
            val rs = Results(listOf(result(1, 100, 1.0, 5), result(2, 100, 1.0, 1), result(3, 50, 2.0, 0)))

            rs.ranked(byRate = false).map { it.rank to it.result.userId } shouldBe listOf(1 to 2L, 1 to 1L, 3 to 3L)
        }

        @Test
        fun `수익률 랭킹은 따로 매긴다`() {
            Results(listOf(result(1, 100, 1.0, 0), result(2, 50, 2.0, 0))).ranked(byRate = true).map { it.result.userId } shouldBe listOf(2L, 1L)
        }

        @Test
        fun `TOP 5와 내 순위`() {
            val rs = Results((1L..8L).map { result(it, 1000 - it, 1.0, 0) })

            rs.top(byRate = false).map { it.rank } shouldBe listOf(1, 2, 3, 4, 5)
            rs.rankOf(8, byRate = false)!!.rank shouldBe 8
            rs.rankOf(99, byRate = false).shouldBeNull()
        }
    }
}
