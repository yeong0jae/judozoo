package judozoo.leadingstock.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SignalStateTest {
    /** 측정값을 차례로 흘려보내고 **마지막** 전이 결과만 본다. */
    private fun flow(vararg ratios: Double?): List<SignalEventType> {
        var state = SignalState()
        var last = emptyList<SignalEventType>()
        ratios.forEach { state.advance(it).let { (events, next) -> last = events; state = next } }
        return last
    }

    @Nested
    inner class 거래대금_스파이크 {
        @Test
        fun `배율이 임계 이상으로 처음 튀면 스파이크를 낸다`() {
            flow(4.0) shouldBe listOf(SignalEventType.VOLUME_SPIKE)
        }

        @Test
        fun `식지 않은 채 계속 높으면 다시 내지 않는다`() {
            flow(4.0, 3.5).shouldBeEmpty()
        }

        @Test
        fun `해제 임계 아래로 식었다가 다시 넘기면 재발화한다`() {
            flow(4.0, 1.5, 3.2) shouldBe listOf(SignalEventType.VOLUME_SPIKE)
        }

        /** 임계 거래대금 미달은 측정값이 없는 것과 같다 — 식은 것으로 본다. */
        @Test
        fun `측정값이 사라지면 해제되어 다음에 다시 낸다`() {
            flow(4.0, null, 3.0) shouldBe listOf(SignalEventType.VOLUME_SPIKE)
        }
    }
}
