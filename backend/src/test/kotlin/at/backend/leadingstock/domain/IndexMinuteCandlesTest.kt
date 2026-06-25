package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class IndexMinuteCandlesTest : FunSpec({

    val base = LocalDateTime.of(2026, 6, 25, 9, 0)
    fun tick(minute: Int, second: Int, value: Double) =
        IndexTick(base.plusMinutes(minute.toLong()).plusSeconds(second.toLong()), value)

    context("틱→1분봉 합성") {
        test("같은 분의 첫 틱이 시가, 마지막 틱이 종가가 된다") {
            val candles = IndexMinuteCandles.fromTicks(
                listOf(tick(0, 30, 105.0), tick(0, 0, 100.0), tick(0, 50, 110.0)),
            )
            candles.trailingStreak() shouldBe CandleStreak(NetTradeSide.BUY, 1, base)
        }
    }

    context("같은 색 연속 길이") {
        // 분마다 (시가, 종가)로 양/음봉을 만든다.
        fun candlesOf(vararg closesOverOpen: Boolean): IndexMinuteCandles {
            val ticks = closesOverOpen.flatMapIndexed { i, bull ->
                val open = 100.0
                val close = if (bull) 101.0 else 99.0
                listOf(tick(i, 0, open), tick(i, 50, close))
            }
            return IndexMinuteCandles.fromTicks(ticks)
        }

        test("최근 봉부터 같은 색이 이어진 개수를 센다") {
            // 음, 양, 양, 양, 양 → 최근 4연속 양봉
            val streak = candlesOf(false, true, true, true, true).trailingStreak()
            streak!!.side shouldBe NetTradeSide.BUY
            streak.count shouldBe 4
        }

        test("색이 바뀌는 지점에서 연속이 끊긴다") {
            // 양, 음, 음 → 최근 2연속 음봉
            val streak = candlesOf(true, false, false).trailingStreak()
            streak!!.side shouldBe NetTradeSide.SELL
            streak.count shouldBe 2
        }

        test("진행 중인 마지막 분을 제외하면 그 직전까지로 센다") {
            // 양,양,양,양 + 마지막(진행중) 음봉 → 제외하면 4연속 양봉
            val candles = candlesOf(true, true, true, true, false)
            val excluded = base.plusMinutes(4) // 5번째(index4) 봉의 분
            val streak = candles.trailingStreak(excludeMinute = excluded)
            streak!!.side shouldBe NetTradeSide.BUY
            streak.count shouldBe 4
        }

        test("보합(시가=종가) 봉이 최신이면 연속이 없다") {
            val ticks = listOf(tick(0, 0, 100.0), tick(0, 50, 100.0))
            IndexMinuteCandles.fromTicks(ticks).trailingStreak().shouldBeNull()
        }
    }
})
