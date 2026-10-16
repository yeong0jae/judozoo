package judozoo.insight.domain

import io.kotest.matchers.shouldBe
import judozoo.insight.domain.Circuit.State
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

class CircuitTest {
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 9, 28, h, m)

    private fun opened() = Circuit().apply { repeat(3) { record(Failure.TIMEOUT, at(10, 0)) } }

    @Nested
    inner class 서킷 {
        @Test
        fun `처음엔 닫혀 있다`() {
            Circuit().state(at(10, 0)) shouldBe State.CLOSED
        }

        @Test
        fun `풀 포화가 반복되어도 서킷을 열지 않는다`() {
            val c = Circuit().apply { repeat(3) { record(Failure.POOL_TIMEOUT, at(10, 0)) } }

            c.failures shouldBe 0
            c.state(at(10, 0)) shouldBe State.CLOSED
        }

        @Test
        fun `풀 포화는 이전 외부 실패 횟수를 유지한다`() {
            val c = Circuit()
            c.record(Failure.TIMEOUT, at(10, 0))
            c.record(Failure.TIMEOUT, at(10, 0))
            c.record(Failure.POOL_TIMEOUT, at(10, 1))

            c.failures shouldBe 2
            c.state(at(10, 1)) shouldBe State.CLOSED
            c.record(Failure.TIMEOUT, at(10, 2))
            c.state(at(10, 2)) shouldBe State.OPEN
        }

        @ParameterizedTest
        @ValueSource(ints = [1, 6])
        fun `풀 포화는 열림과 시험 상태도 유지한다`(minute: Int) {
            val c = opened()
            val before = c.state(at(10, minute))

            c.record(Failure.POOL_TIMEOUT, at(10, minute))

            c.openUntil shouldBe at(10, 5)
            c.state(at(10, minute)) shouldBe before
        }

        @Test
        fun `일시 장애가 세 번 이어지면 5분 동안 연다`() {
            val c = Circuit()
            listOf(0, 0, 1).forEach { c.record(Failure.TIMEOUT, at(10, it)) }

            c.state(at(10, 5)) shouldBe State.OPEN
            c.state(at(10, 6)) shouldBe State.HALF_OPEN
        }

        @Test
        fun `사이에 성공이 끼면 처음부터 센다`() {
            val c = Circuit()
            c.record(Failure.TIMEOUT, at(10, 0))
            c.record(Failure.UNAVAILABLE, at(10, 0))
            c.record(null, at(10, 0))
            c.record(Failure.TIMEOUT, at(10, 1))

            c.state(at(10, 1)) shouldBe State.CLOSED
        }

        @Test
        fun `답이 이상한 건 Vertex가 답한 것이라 세지 않는다`() {
            val c = Circuit()
            c.record(Failure.TIMEOUT, at(10, 0))
            c.record(Failure.TIMEOUT, at(10, 0))
            c.record(Failure.BAD_ANSWER, at(10, 0))
            c.record(Failure.TIMEOUT, at(10, 1))

            c.state(at(10, 1)) shouldBe State.CLOSED
        }

        @Test
        fun `시험에 성공하면 닫고 실패하면 다시 5분 연다`() {
            opened().apply { record(null, at(10, 6)) }.state(at(10, 6)) shouldBe State.CLOSED

            val failedTrial = opened().apply { record(Failure.TIMEOUT, at(10, 6)) }
            failedTrial.state(at(10, 10)) shouldBe State.OPEN
            failedTrial.state(at(10, 11)) shouldBe State.HALF_OPEN
        }

        @Test
        fun `클라이언트 오류는 사람이 고칠 때까지 열어 둔다`() {
            Circuit().apply { record(Failure.CLIENT_ERROR, at(10, 0)) }.state(at(10, 0).plusDays(1)) shouldBe State.OPEN
        }
    }
}
