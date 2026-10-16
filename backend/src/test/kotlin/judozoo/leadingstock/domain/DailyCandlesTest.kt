package judozoo.leadingstock.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DailyCandlesTest {
    private val today = LocalDate.of(2026, 6, 9)

    private fun daily(daysAgo: Long, volume: Long) = DailyCandle(today.minusDays(daysAgo), 1000, 1000, 1000, 1000, volume, 0.0)

    @Nested
    inner class 상대거래량 {
        @Test
        fun `현재 누적 거래량이 직전 평균의 몇 배인지 낸다`() {
            DailyCandles(listOf(daily(1, 100), daily(2, 100), daily(3, 100))).relativeVolumeFrom(300, today, 3) shouldBe 3.0
        }

        @Test
        fun `오늘 봉이 섞여 있어도 기준선에서 뺀다`() {
            DailyCandles(listOf(daily(0, 100), daily(1, 200), daily(2, 200))).relativeVolumeFrom(600, today, 20) shouldBe 3.0
        }

        @Test
        fun `기준선은 최신 N거래일만 쓴다`() {
            DailyCandles(listOf(daily(1, 200), daily(2, 200), daily(3, 1000))).relativeVolumeFrom(400, today, 2) shouldBe 2.0
        }

        @Test
        fun `현재 거래량을 모르면 없다`() {
            DailyCandles(listOf(daily(1, 100))).relativeVolumeFrom(null, today, 20).shouldBeNull()
        }

        @Test
        fun `직전 거래일 데이터가 없으면 없다`() {
            DailyCandles(listOf(daily(0, 300))).relativeVolumeFrom(300, today, 20).shouldBeNull()
        }

        @Test
        fun `직전 평균이 0이면 없다`() {
            DailyCandles(listOf(daily(1, 0))).relativeVolumeFrom(300, today, 20).shouldBeNull()
        }
    }
}
