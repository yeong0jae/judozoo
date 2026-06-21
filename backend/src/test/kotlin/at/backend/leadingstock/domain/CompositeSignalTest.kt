package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class CompositeSignalTest : FunSpec({

    val at = LocalDateTime.of(2026, 6, 12, 10, 30)
    val nearGap = 2.0 // 갭 < 2%면 돌파 신호 켜짐(근접 이상)

    fun breakout(gapRate: Double) = SwingHighSignal(peakPrice = 1000, peakAt = at, gapRate = gapRate)
    fun spike(ratio: Double) = VolumeSpike(latestTradingValue = 2_000_000_000, at = at, ratio = ratio)

    context("돌파 신호 인정 거리") {
        test("돌파선까지 근접(갭 < 임계)하면 돌파 신호가 켜진다") {
            val signal = CompositeSignal(breakout(gapRate = 0.5), spike = null, breakoutNearGapRate = nearGap)
            signal.breakout.shouldNotBeNull()
            signal.signalCount shouldBe 1
        }

        test("이미 돌파(갭 0 이하)해도 켜진 신호로 본다") {
            val signal = CompositeSignal(breakout(gapRate = -1.2), spike = null, breakoutNearGapRate = nearGap)
            signal.breakout.shouldNotBeNull()
        }

        test("관망 거리(갭 >= 임계)면 돌파 신호를 떨군다") {
            val signal = CompositeSignal(breakout(gapRate = 3.0), spike = null, breakoutNearGapRate = nearGap)
            signal.breakout.shouldBeNull()
            signal.signalCount shouldBe 0
        }
    }

    context("교차 강도") {
        test("돌파와 스파이크가 함께 켜지면 신호 수가 2다") {
            val signal = CompositeSignal(breakout(gapRate = 0.3), spike(ratio = 5.2), breakoutNearGapRate = nearGap)
            signal.signalCount shouldBe 2
        }

        test("스파이크만 켜지면 신호 수가 1이고 돌파는 비어 있다") {
            val signal = CompositeSignal(breakout = null, spike = spike(ratio = 4.0), breakoutNearGapRate = nearGap)
            signal.signalCount shouldBe 1
            signal.breakout.shouldBeNull()
            signal.spike.shouldNotBeNull()
        }

        test("둘 다 없으면 신호 수가 0이다(주도주 풀에만 든 종목)") {
            val signal = CompositeSignal(breakout = null, spike = null, breakoutNearGapRate = nearGap)
            signal.signalCount shouldBe 0
        }
    }
})
