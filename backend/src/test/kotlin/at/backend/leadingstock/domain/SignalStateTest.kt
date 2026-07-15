package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SignalStateTest : FunSpec({

    fun reading(
        gap: Double? = null,
        peak: Long? = null,
        spike: Double? = null,
    ) = SignalReading(
        gapRate = gap, peakPrice = peak, spikeRatio = spike,
        ma20CrossedUp = null, ma20BelowBand = null,
    )

    /** 측정값을 차례로 흘려보내며 마지막 전이 결과만 본다. */
    fun SignalState.feed(vararg readings: SignalReading): Pair<List<SignalEventType>, SignalState> {
        var state = this
        var last: List<SignalEventType> = emptyList()
        readings.forEach {
            val (events, next) = state.advance(it)
            last = events
            state = next
        }
        return last to state
    }

    context("돌파") {
        test("전고점까지 갭이 0 이하로 처음 내려가면 돌파 이벤트를 낸다") {
            val (events, _) = SignalState.INITIAL.advance(reading(gap = -0.1, peak = 1000))
            events shouldContainExactly listOf(SignalEventType.BREAKOUT)
        }

        test("돌파 상태가 유지되는 동안에는 다시 내지 않는다") {
            val (_, broken) = SignalState.INITIAL.advance(reading(gap = -0.1, peak = 1000))
            val (events, _) = broken.advance(reading(gap = -0.5, peak = 1000))
            events.shouldBeEmpty()
        }

        test("눌렸다가 같은 전고점을 다시 깨면 새 이벤트로 보지 않는다") {
            // 돌파 → 갭 0.6%로 눌림(재무장) → 다시 갭 0 이하지만 전고점 그대로
            val (events, _) = SignalState.INITIAL.feed(
                reading(gap = -0.1, peak = 1000),
                reading(gap = 0.6, peak = 1000),
                reading(gap = -0.1, peak = 1000),
            )
            events.shouldBeEmpty()
        }

        test("눌렸다가 더 높은 전고점을 재돌파하면 다시 이벤트를 낸다") {
            val (events, _) = SignalState.INITIAL.feed(
                reading(gap = -0.1, peak = 1000),
                reading(gap = 0.6, peak = 1000),
                reading(gap = -0.1, peak = 1100),
            )
            events shouldContainExactly listOf(SignalEventType.BREAKOUT)
        }
    }

    context("돌파 임박") {
        test("갭이 2% 미만으로 처음 접근하면 임박 이벤트를 낸다") {
            val (events, _) = SignalState.INITIAL.advance(reading(gap = 1.5, peak = 1000))
            events shouldContainExactly listOf(SignalEventType.BREAKOUT_IMMINENT)
        }

        test("임박 구간에 머무는 동안에는 다시 내지 않는다") {
            val (events, _) = SignalState.INITIAL.feed(
                reading(gap = 0.7, peak = 1000),
                reading(gap = 0.4, peak = 1000),
            )
            events.shouldBeEmpty()
        }

        test("충분히 물러났다가(>=2.5%) 다시 접근하면 임박을 재발화한다") {
            val (events, _) = SignalState.INITIAL.feed(
                reading(gap = 0.7, peak = 1000),
                reading(gap = 3.0, peak = 1000),
                reading(gap = 0.5, peak = 1000),
            )
            events shouldContainExactly listOf(SignalEventType.BREAKOUT_IMMINENT)
        }
    }

    context("거래대금 스파이크") {
        test("배율이 임계(2.5배) 이상으로 처음 튀면 스파이크 이벤트를 낸다") {
            val (events, _) = SignalState.INITIAL.advance(reading(spike = 4.0))
            events shouldContainExactly listOf(SignalEventType.VOLUME_SPIKE)
        }

        test("식지(2배 미만) 않은 채 계속 높으면 다시 내지 않는다") {
            val (events, _) = SignalState.INITIAL.feed(reading(spike = 4.0), reading(spike = 3.5))
            events.shouldBeEmpty()
        }

        test("2배 아래로 식었다가 다시 임계를 넘기면 재발화한다") {
            val (events, _) = SignalState.INITIAL.feed(
                reading(spike = 4.0),
                reading(spike = 1.5),
                reading(spike = 3.2),
            )
            events shouldContainExactly listOf(SignalEventType.VOLUME_SPIKE)
        }
    }

    context("동시 전이") {
        test("돌파와 스파이크가 한 시점에 함께 켜지면 둘 다 낸다") {
            val (events, _) = SignalState.INITIAL.advance(reading(gap = -0.1, peak = 1000, spike = 5.0))
            events shouldContainExactly listOf(SignalEventType.BREAKOUT, SignalEventType.VOLUME_SPIKE)
        }
    }

    test("측정값이 없으면(분봉 미존재) 아무 전이도 없고 상태가 보존된다") {
        val (_, imminentState) = SignalState.INITIAL.advance(reading(gap = 0.7, peak = 1000))
        val (events, _) = imminentState.advance(reading())
        events.shouldBeEmpty()
    }
})
