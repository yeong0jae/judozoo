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

    @Nested
    inner class 거래대금_스파이크 {
        private fun valued(minute: Long, value: Long) = MinuteCandle(base.plusMinutes(minute), 0, 0, 0, 0, 0, value)

        private fun candle(minute: Long, open: Long, close: Long) = MinuteCandle(base.plusMinutes(minute), open, maxOf(open, close) + 2, minOf(open, close) - 1, close, 0, 500)

        private val flat = (0L until 3).map { MinuteCandle(base.plusMinutes(it), 100, 100, 100, 100, 0, 100) }

        @Test
        fun `최신 봉이 직전 평균의 N배면 배율 N`() {
            val spike = MinuteCandles(listOf(valued(0, 100), valued(1, 100), valued(2, 100), valued(3, 500))).volumeSpike(3)!!

            spike.latestTradingValue shouldBe 500
            spike.at shouldBe base.plusMinutes(3)
            spike.ratio shouldBe (5.0 plusOrMinus 0.001)
        }

        @Test
        fun `직전 평균은 지정한 봉 수로 제한된다`() {
            MinuteCandles(listOf(valued(0, 1000), valued(1, 1000), valued(2, 200), valued(3, 200), valued(4, 400))).volumeSpike(2)!!.ratio shouldBe (2.0 plusOrMinus 0.001)
        }

        @Test
        fun `봉이 하나뿐이면 없다`() {
            MinuteCandles(listOf(valued(0, 100))).volumeSpike(20).shouldBeNull()
        }

        @Test
        fun `직전 평균이 0이면 없다`() {
            MinuteCandles(listOf(valued(0, 0), valued(1, 500))).volumeSpike(20).shouldBeNull()
        }

        @Test
        fun `양봉이면 매수 음봉이면 매도다`() {
            MinuteCandles(flat + candle(3, 100, 118)).volumeSpike(3)!!.direction shouldBe SpikeDirection.BUY
            MinuteCandles(flat + candle(3, 120, 102)).volumeSpike(3)!!.direction shouldBe SpikeDirection.SELL
        }

        @Test
        fun `위로 찔렀다 되밀려도 양봉이면 매수다`() {
            MinuteCandles(flat + MinuteCandle(base.plusMinutes(3), 100, 112, 99, 101, 0, 500)).volumeSpike(3)!!.direction shouldBe SpikeDirection.BUY
        }

        @Test
        fun `고가 근처로 회복해도 음봉이면 매도다`() {
            MinuteCandles(flat + MinuteCandle(base.plusMinutes(3), 110, 111, 98, 109, 0, 500)).volumeSpike(3)!!.direction shouldBe SpikeDirection.SELL
        }

        @Test
        fun `종가가 시가와 같으면 차트처럼 매수다`() {
            MinuteCandles(flat + MinuteCandle(base.plusMinutes(3), 100, 105, 95, 100, 0, 500)).volumeSpike(3)!!.direction shouldBe SpikeDirection.BUY
            MinuteCandles(flat + MinuteCandle(base.plusMinutes(3), 100, 100, 100, 100, 0, 500)).volumeSpike(3)!!.direction shouldBe SpikeDirection.BUY
        }
    }
}
