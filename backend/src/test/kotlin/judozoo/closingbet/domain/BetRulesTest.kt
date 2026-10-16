package judozoo.closingbet.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import judozoo.closingbet.domain.PhaseTest.Companion.DAYS
import judozoo.closingbet.domain.PhaseTest.Companion.FRI
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BetRulesTest {
    private val bet = Phase.at(FRI.atTime(16, 0), DAYS)
    private val pool = listOf("000660", "247540")

    @Nested
    inner class 베팅_규칙 {
        @ParameterizedTest
        @ValueSource(ints = [100, 2_000, 10_000])
        fun `100만부터 1억까지 100만 단위로 건다`(amount: Int) {
            BetRules.check(bet, amount, "000660", pool, 412_500) shouldBeGreaterThanOrEqual 1
        }

        @ParameterizedTest
        @ValueSource(ints = [0, 50, 150, 10_100])
        fun `범위나 단위를 벗어나면 받지 않는다`(amount: Int) {
            shouldThrow<BetRejected> { BetRules.check(bet, amount, "000660", pool, 412_500) }
        }

        @Test
        fun `목록에 없는 종목은 고를 수 없다`() {
            shouldThrow<BetRejected> { BetRules.check(bet, 1_000, "005930", pool, 70_000) }.message!! shouldContain "목록"
        }

        @Test
        fun `베팅 시간 밖에는 받지 않는다`() {
            shouldThrow<BetRejected> { BetRules.check(Phase.at(FRI.atTime(20, 0), DAYS), 1_000, "000660", pool, 412_500) }.message!! shouldContain "15:00"
        }

        @Test
        fun `주식 수는 금액을 가격으로 나눈 몫이다`() {
            BetRules.sharesFor(2_000, 184_500) shouldBe 108
        }

        @Test
        fun `한 주도 못 사면 베팅할 수 없다`() {
            shouldThrow<BetRejected> { BetRules.sharesFor(100, 1_284_000) }.message!! shouldContain "한 주"
        }
    }
}
