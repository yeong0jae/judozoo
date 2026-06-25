package at.backend.leadingstock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** 코스피 기준 단계 1조(10,000억), 완충 1,000억으로 검증한다. */
class InvestorNetBuyStateTest : FunSpec({

    val step = 10_000L
    val buffer = 1_000L

    fun InvestorNetBuyState.feed(netEok: Long) = advance(netEok, step, buffer)

    context("단계 승급") {
        test("누적 순매수가 한 단계선에 닿으면 그 단계로 전이된다") {
            val (transition, _) = InvestorNetBuyState.INITIAL.feed(10_000)
            transition shouldBe NetBuyTransition(NetTradeSide.BUY, 1)
        }

        test("단계선에 못 미치면 전이가 없다") {
            val (transition, _) = InvestorNetBuyState.INITIAL.feed(9_000)
            transition.shouldBeNull()
        }

        test("연이어 다음 단계선을 넘으면 다음 단계로 전이된다") {
            val (_, atOne) = InvestorNetBuyState.INITIAL.feed(10_000)
            val (transition, _) = atOne.feed(20_000)
            transition shouldBe NetBuyTransition(NetTradeSide.BUY, 2)
        }

        test("한 번에 두 단계선을 건너뛰어도 도달한 최종 단계만 전이된다") {
            val (transition, _) = InvestorNetBuyState.INITIAL.feed(25_000)
            transition shouldBe NetBuyTransition(NetTradeSide.BUY, 2)
        }
    }

    context("경계 떨림 흡수") {
        test("승급 직후 완충 안에서 오르내려도 같은 단계가 다시 발화하지 않는다") {
            val (_, atTwo) = InvestorNetBuyState.INITIAL.feed(20_000)
            val (down, afterDown) = atTwo.feed(19_500) // 20,000-1,000=19,000 위 → 유지
            val (up, _) = afterDown.feed(20_500)       // 다음 단계선(30,000) 미달 → 유지
            down.shouldBeNull()
            up.shouldBeNull()
        }
    }

    context("단계 강등(완화 회복)") {
        test("완충을 넘어 떨어지면 한 단계 낮은 전이가 발생한다") {
            val (_, atTwo) = InvestorNetBuyState.INITIAL.feed(20_000)
            val (transition, _) = atTwo.feed(18_900) // 19,000 아래 → 1단계로 강등
            transition shouldBe NetBuyTransition(NetTradeSide.BUY, 1)
        }

        test("중립(0)으로 완전히 복귀하면 전이가 없다") {
            val (_, atOne) = InvestorNetBuyState.INITIAL.feed(10_000)
            val (transition, _) = atOne.feed(0)
            transition.shouldBeNull()
        }
    }

    context("방향 전환") {
        test("순매도 단계에서 순매수로 뒤집히면 단계를 리셋하고 반대편에서 다시 센다") {
            val (sell, atSellTwo) = InvestorNetBuyState.INITIAL.feed(-20_000)
            sell shouldBe NetBuyTransition(NetTradeSide.SELL, 2)
            val (buy, _) = atSellTwo.feed(12_000)
            buy shouldBe NetBuyTransition(NetTradeSide.BUY, 1)
        }

        test("순매도도 단계선에 닿으면 전이된다") {
            val (transition, _) = InvestorNetBuyState.INITIAL.feed(-10_000)
            transition shouldBe NetBuyTransition(NetTradeSide.SELL, 1)
        }
    }
})
