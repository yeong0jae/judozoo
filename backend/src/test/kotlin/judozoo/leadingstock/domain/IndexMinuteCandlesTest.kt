package judozoo.leadingstock.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class IndexMinuteCandlesTest {
    private val open = LocalDateTime.of(2026, 9, 11, 9, 0)

    @Nested
    inner class 틱으로_1분봉_합성 {
        @Test
        fun `그 분 첫 틱이 시가, 마지막 틱이 종가, 고저는 극값이고 거래량은 합이다`() {
            val ticks = listOf(
                IndexTick(open.plusSeconds(50), 101.0, 3), IndexTick(open.plusSeconds(10), 100.0, 1),
                IndexTick(open.plusSeconds(30), 104.0, 2), IndexTick(open.plusSeconds(70), 99.0, 5),
            )

            IndexMinuteCandles.fromTicks(ticks).candles() shouldBe listOf(
                IndexMinuteCandle(open, 100.0, 104.0, 100.0, 101.0, 6),
                IndexMinuteCandle(open.plusMinutes(1), 99.0, 99.0, 99.0, 99.0, 5),
            )
        }
    }

    @Nested
    inner class 이평_돌파 {
        private fun bars(vararg closes: Double) = IndexMinuteCandles(
            (closes.toList() + 0.0).mapIndexed { i, c -> IndexMinuteCandle(open.plusMinutes(i * 5L), c, c, c, c, 0) },
        ).movingAverage(5, 20, 0.005)

        @Test
        fun `아래에 있다가 이평 위로 마감하면 반등이다`() {
            val signal = bars(*DoubleArray(20) { 100.0 }, 99.0, 102.0)!!

            signal.crossedUp shouldBe true
            signal.crossedDown shouldBe false
        }

        @Test
        fun `위에 있다가 이평 아래로 마감하면 꺾임이다`() {
            val signal = bars(*DoubleArray(20) { 100.0 }, 101.0, 98.0)!!

            signal.crossedDown shouldBe true
            signal.crossedUp shouldBe false
        }

        @Test
        fun `이평에서 마진 넘게 밀려나면 재무장 구간이다`() {
            val below = bars(*DoubleArray(21) { 100.0 }, 99.0)!!
            val above = bars(*DoubleArray(21) { 100.0 }, 101.0)!!

            below.belowBand shouldBe true
            above.aboveBand shouldBe true
        }

        @Test
        fun `끝난 봉이 이평 기간보다 하나 넘게 있어야 판정한다`() {
            bars(*DoubleArray(20) { 100.0 }).shouldBeNull()
        }
    }
}
