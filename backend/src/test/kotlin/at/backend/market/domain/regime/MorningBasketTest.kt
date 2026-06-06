package at.backend.market.domain.regime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class MorningBasketTest : FunSpec({

    context("아침 NXT 바스켓 — Gap1") {
        test("전일 종가 대비 08:15 등락률을 거래대금 가중으로 평균한다") {
            val basket = MorningBasket(
                listOf(
                    BasketConstituent("A", rateAt0815 = 2.0, weight = 60),
                    BasketConstituent("B", rateAt0815 = -1.0, weight = 40),
                ),
            )
            // (2.0×60 + (−1.0)×40) / 100 = 0.8
            basket.gap1 shouldBe (0.8 plusOrMinus 0.0001)
        }
    }

    context("아침 NXT 바스켓 — Gap2") {
        test("08:15 대비 현재 등락으로 산출하고 모두 살아있으면 신뢰한다") {
            val basket = MorningBasket(listOf(BasketConstituent("A", rateAt0815 = 2.0, weight = 100)))

            // 08:15 +2.0% → 현재 +3.0% : (1.03/1.02 − 1) ≈ +0.98%
            val gap2 = basket.gap2(mapOf("A" to 3.0))

            gap2.value shouldBe (0.98 plusOrMinus 0.02)
            gap2.coverage shouldBe (1.0 plusOrMinus 0.0001)
            gap2.reliable shouldBe true
        }

        test("랭킹에서 빠진 종목은 제외하고, 빠진 가중이 크면 신뢰낮음으로 표시한다") {
            val basket = MorningBasket(
                listOf(
                    BasketConstituent("A", rateAt0815 = 1.0, weight = 80),
                    BasketConstituent("B", rateAt0815 = 1.0, weight = 20),
                ),
            )
            // B가 현재 시세에 없음 → coverage 0.8 < 0.95
            val gap2 = basket.gap2(mapOf("A" to 1.0))

            gap2.coverage shouldBe (0.8 plusOrMinus 0.0001)
            gap2.reliable shouldBe false
        }

        test("현재 시세가 하나도 없으면 coverage 0이다") {
            val basket = MorningBasket(listOf(BasketConstituent("A", rateAt0815 = 1.0, weight = 100)))

            val gap2 = basket.gap2(emptyMap())

            gap2.coverage shouldBe 0.0
            gap2.reliable shouldBe false
        }
    }
})
