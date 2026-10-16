package judozoo.leadingstock.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class MinuteCandlesTest {
    private val base = LocalDateTime.of(2026, 6, 12, 9, 0)

    /** 저항선 판정은 고가만, 지지선 판정은 저가만 쓴다 — 나머지 필드는 영향 없음. */
    private fun bar(minute: Long, price: Long) = MinuteCandle(base.plusMinutes(minute), price, price, price, price, 0, 0)

    @Nested
    inner class 저항선 {
        @Test
        fun `최고가를 전고점으로 잡고 잔여 상승률을 낸다`() {
            val signal = MinuteCandles(listOf(bar(0, 100), bar(1, 105), bar(2, 108), bar(3, 106), bar(4, 102))).peakSignal(104)!!

            signal.peakPrice shouldBe 108
            signal.peakAt shouldBe base.plusMinutes(2)
            signal.gapRate shouldBe ((108 - 104) / 104.0 * 100 plusOrMinus 0.001)
        }

        @Test
        fun `같은 최고가가 여러 번이면 처음 형성된 봉을 잡는다`() {
            MinuteCandles(listOf(bar(0, 100), bar(1, 108), bar(2, 103), bar(3, 105), bar(4, 108))).peakSignal(104)!!.peakAt shouldBe base.plusMinutes(1)
        }

        @Test
        fun `현재가가 고가에 도달하면 잔여 상승률은 0`() {
            MinuteCandles(listOf(bar(0, 100), bar(1, 105), bar(2, 110))).peakSignal(110)!!.gapRate shouldBe (0.0 plusOrMinus 0.001)
        }

        @Test
        fun `입력이 시간 역순이어도 정렬해 같게 판정한다`() {
            val signal = MinuteCandles(listOf(bar(4, 102), bar(3, 106), bar(2, 108), bar(1, 105), bar(0, 100))).peakSignal(104)!!

            (signal.peakPrice to signal.peakAt) shouldBe (108L to base.plusMinutes(2))
        }

        @Test
        fun `분봉이 없으면 없다`() {
            MinuteCandles(emptyList()).peakSignal(1000).shouldBeNull()
        }

        @Test
        fun `현재가가 0 이하면 없다`() {
            MinuteCandles(listOf(bar(0, 100), bar(1, 105))).peakSignal(0).shouldBeNull()
        }
    }

    /** 저가 근접도 — 저항선(고가)의 대칭. */
    @Nested
    inner class 지지선 {
        @Test
        fun `최저가를 지지선으로 잡는다`() {
            val signal = MinuteCandles(listOf(bar(0, 1000), bar(1, 950), bar(2, 980))).troughSignal(1000)!!

            signal.troughPrice shouldBe 950
            signal.troughAt shouldBe base.plusMinutes(1)
        }

        @Test
        fun `저가까지 남은 하락률을 준다`() {
            MinuteCandles(listOf(bar(0, 900))).troughSignal(1000)!!.gapRate shouldBe (10.0 plusOrMinus 1e-9)
        }

        @Test
        fun `이미 저가에 닿았으면 0`() {
            MinuteCandles(listOf(bar(0, 1000))).troughSignal(1000)!!.gapRate shouldBe (0.0 plusOrMinus 1e-9)
        }

        @Test
        fun `저가를 깨고 내려갔으면 음수`() {
            MinuteCandles(listOf(bar(0, 1000))).troughSignal(900)!!.gapRate shouldBeLessThan 0.0
        }

        @Test
        fun `같은 저가가 여럿이면 처음 봉을 잡는다`() {
            MinuteCandles(listOf(bar(0, 950), bar(5, 950))).troughSignal(1000)!!.troughAt shouldBe base
        }

        @Test
        fun `봉이 없으면 시그널도 없다`() {
            MinuteCandles(emptyList()).troughSignal(1000).shouldBeNull()
        }

        @Test
        fun `현재가가 0 이하면 시그널이 없다`() {
            MinuteCandles(listOf(bar(0, 900))).troughSignal(0).shouldBeNull()
        }
    }
}
