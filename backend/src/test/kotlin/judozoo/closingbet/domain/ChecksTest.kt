package judozoo.closingbet.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import judozoo.closingbet.domain.PhaseTest.Companion.FRI
import judozoo.closingbet.domain.PhaseTest.Companion.THU
import judozoo.leadingstock.domain.DailyCandle
import judozoo.leadingstock.domain.DailyCandles
import judozoo.leadingstock.domain.MinuteCandle
import judozoo.leadingstock.domain.MinuteCandles
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

class ChecksTest {
    private fun bar(day: LocalDate, h: Int, m: Int, price: Long) = MinuteCandle(day.atTime(h, m), price, price, price, price, 10, price * 10)

    @Nested
    inner class 종베_체크와_등급 {
        @Test
        fun `여섯 가지를 모두 채우면 S`() {
            val c = Checks.of(412_500, 414_000, 2640, 910, 740, -0.4, -5.0, 410_000)

            (c.passed to c.grade) shouldBe (6 to Grade.S)
        }

        @Test
        fun `고가에서 1·5퍼센트 넘게 밀리면 고가 마감이 아니다`() {
            Checks.of(985, 1000, 1, 1, 1, null, -5.0, null).nearHigh shouldBe true
            Checks.of(984, 1000, 1, 1, 1, null, -5.0, null).nearHigh shouldBe false
        }

        @ParameterizedTest
        @CsvSource("5,A", "4,B", "3,C", "0,C")
        fun `채운 개수로 등급을 매긴다`(passed: Int, grade: Grade) {
            val on = List(6) { it < passed }
            fun net(i: Int) = if (on[i]) 1L else -1L
            val c = Checks.of(if (on[0]) 1000 else 900, 1000, net(1), net(2), net(3), if (on[4]) 0.0 else -10.0, -5.0, if (on[5]) 900 else 1100)

            (c.passed to c.grade) shouldBe (passed to grade)
        }

        @Test
        fun `순매수가 0이면 샀다고 보지 않는다`() {
            Checks.of(1000, 1000, 0, 0, 0, null, -5.0, null).foreign shouldBe false
        }
    }

    @Nested
    inner class 애프터_버팀 {
        @ParameterizedTest
        @CsvSource("1000,1000,true", "1001,1000,true", "999,1000,false")
        fun `20시 종가가 정규장 종가 이상이면 맞아요`(close: Long, regular: Long, ok: Boolean) {
            Checks.of(close, close, 1, 1, 1, null, -5.0, regular).afterHold shouldBe ok
        }

        @Test
        fun `정규장이 안 끝났으면 통과로 세지 않는다`() {
            Checks.of(1000, 1000, 1, 1, 1, null, -5.0, null).afterHold shouldBe false
        }

        @Test
        fun `정규장 종가는 15시30분까지의 마지막 체결이다`() {
            MinuteCandles(listOf(bar(THU, 15, 19, 101), bar(THU, 15, 30, 105), bar(THU, 15, 41, 99), bar(THU, 19, 59, 98))).closeAt(THU, Checks.REGULAR_CLOSE) shouldBe 105
        }

        @Test
        fun `15시30분 봉이 없어도 그 전 마지막 체결로 잡는다`() {
            MinuteCandles(listOf(bar(THU, 15, 28, 103), bar(THU, 16, 1, 99))).closeAt(THU, Checks.REGULAR_CLOSE) shouldBe 103
        }

        @Test
        fun `정규장이 끝나기 전에는 잡지 않는다`() {
            MinuteCandles(listOf(bar(THU, 15, 10, 101))).closeAt(THU, Checks.REGULAR_CLOSE).shouldBeNull()
        }

        @Test
        fun `다른 날 봉은 보지 않는다`() {
            MinuteCandles(listOf(bar(LocalDate.of(2026, 9, 30), 15, 30, 100))).closeAt(THU, Checks.REGULAR_CLOSE).shouldBeNull()
        }
    }

    @Nested
    inner class 최근_고점과의_거리 {
        private fun history(highs: Map<Int, Long>, filler: Long = 100) = (1 until 100).map { age ->
            val high = highs[age] ?: filler
            DailyCandle(FRI.minusDays(age.toLong()), high, high, high, high, 1, 0.0)
        }

        @Test
        fun `직전 60거래일 최고가 대비 퍼센트`() {
            DailyCandles(history(mapOf(30 to 200))).gapFromRecentHigh(190, FRI, Checks.RECENT_HIGH_DAYS)!! shouldBe (-5.0 plusOrMinus 1e-9)
        }

        @Test
        fun `60거래일보다 오래된 고점은 보지 않는다`() {
            DailyCandles(history(mapOf(80 to 500))).gapFromRecentHigh(100, FRI, Checks.RECENT_HIGH_DAYS)!! shouldBe (0.0 plusOrMinus 1e-9)
        }

        @Test
        fun `고점을 넘어서면 플러스다`() {
            DailyCandles(history(emptyMap())).gapFromRecentHigh(110, FRI, Checks.RECENT_HIGH_DAYS)!! shouldBe (10.0 plusOrMinus 1e-9)
        }

        @Test
        fun `과거 일봉이 없으면 재지 않는다`() {
            DailyCandles(emptyList()).gapFromRecentHigh(100, FRI, Checks.RECENT_HIGH_DAYS).shouldBeNull()
        }

        @ParameterizedTest
        @CsvSource("-5.0,true", "-4.9,true", "-5.1,false", ",false")
        fun `기준 안이면 맞아요`(gap: Double?, ok: Boolean) {
            Checks.of(1000, 1000, 1, 1, 1, gap, -5.0, null).recentHigh shouldBe ok
        }
    }
}
