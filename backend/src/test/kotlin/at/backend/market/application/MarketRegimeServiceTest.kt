package at.backend.market.application

import at.backend.leadingstock.domain.LeadingStockSnapshot
import at.backend.library.time.TimeProvider
import at.backend.platform.kiwoom.client.KiwoomMarketClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime

class MarketRegimeServiceTest : FunSpec({

    fun snap(code: String, name: String, rate: Double, tradingValue: Long) =
        LeadingStockSnapshot(
            stockCode = code,
            stockName = name,
            currentPrice = 1000,
            priceChangeRate = rate,
            tradingValueRank = 1,
            accumulatedTradingValue = tradingValue,
        )

    val fixedNow = LocalDateTime.of(2026, 1, 5, 9, 0)
    val time = object : TimeProvider {
        override fun now() = fixedNow
    }

    context("바스켓 구성") {
        test("거래대금 상위에서 ETF/ETN을 제외하고 상위 N만 남긴다") {
            val client = mockk<KiwoomMarketClient>()
            every { client.fetchTopTradingValueStocks(any()) } returns listOf(
                snap("005930", "삼성전자", 1.0, 100),
                snap("999999", "KODEX 레버리지", 5.0, 500), // ETF — 제외
                snap("000660", "SK하이닉스", 1.0, 80),
            )
            val service = MarketRegimeService(client, time, mockk(relaxed = true))

            val basket = service.fetchBasket(size = 2, fetchCount = 50)

            basket.map { it.stockCode } shouldBe listOf("005930", "000660")
        }
    }

    context("두 갭 산출") {
        test("앵커 고정 전엔 gap1은 잠정·gap2는 없음, 고정 후엔 gap1 고정·gap2 산출") {
            val client = mockk<KiwoomMarketClient>()
            val service = MarketRegimeService(client, time, mockk(relaxed = true))
            val morning = listOf(snap("005930", "삼성전자", 2.0, 100))

            // 앵커 고정 전 — gap1은 현재 가중평균(잠정), gap2 없음
            val before = service.compute(morning)
            before.gap1Locked shouldBe false
            before.gap1 shouldBe (2.0 plusOrMinus 0.001)
            before.gap2.shouldBeNull()

            // 08:15 앵커 고정 (08:15가 +2.0%)
            service.captureAnchor(morning)

            // 현재 +3.0% → Gap2 = (1.03/1.02 − 1) ≈ +0.98%
            val after = service.compute(listOf(snap("005930", "삼성전자", 3.0, 100)))
            after.gap1Locked shouldBe true
            after.gap1 shouldBe (2.0 plusOrMinus 0.001) // 08:15 고정값 유지
            after.gap2!! shouldBe (0.98 plusOrMinus 0.02)
            after.gap2Reliable shouldBe true
        }
    }
})
