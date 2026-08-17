package at.backend.market.application

import at.backend.common.test.IntegrationTestBase
import at.backend.market.domain.FuturesInvestorSnapshot
import at.backend.market.infrastructure.repository.FuturesInvestorSnapshotRepository
import at.backend.stock.domain.Market
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalTime

class MarketFuturesInvestorServiceTest(
    private val service: MarketFuturesInvestorService,
    private val repository: FuturesInvestorSnapshotRepository,
) : IntegrationTestBase() {

    init {
        beforeTest { repository.deleteAll() }

        context("선물 일별 수급 히스토리") {
            test("거래일마다 마지막 스냅샷의 당일 누적을 최신 날짜순으로 준다") {
                saveSnapshot(Market.KOSPI, DAY1, LocalTime.of(9, 0), foreign = 100)
                saveSnapshot(Market.KOSPI, DAY1, LocalTime.of(15, 45), foreign = 700)
                saveSnapshot(Market.KOSPI, DAY2, LocalTime.of(12, 0), foreign = 200)
                saveSnapshot(Market.KOSPI, DAY2, LocalTime.of(15, 40), foreign = -500)

                val days = service.dailyHistory(Market.KOSPI, 10)

                days.map { it.date } shouldBe listOf(DAY2, DAY1)
                days.map { it.nets.foreign } shouldBe listOf(-500L, 700L)
            }

            test("요청한 일수만큼만 최근 거래일을 준다") {
                saveSnapshot(Market.KOSPI, DAY1, LocalTime.of(15, 45), foreign = 700)
                saveSnapshot(Market.KOSPI, DAY2, LocalTime.of(15, 45), foreign = -500)

                val days = service.dailyHistory(Market.KOSPI, 1)

                days.map { it.date } shouldBe listOf(DAY2)
            }

            test("스냅샷이 하나도 없으면 빈 목록") {
                service.dailyHistory(Market.KOSPI, 10) shouldBe emptyList()
            }

            test("다른 시장의 스냅샷은 섞이지 않는다") {
                saveSnapshot(Market.KOSPI, DAY1, LocalTime.of(15, 45), foreign = 700)
                saveSnapshot(Market.KOSDAQ, DAY1, LocalTime.of(15, 45), foreign = -300)

                service.dailyHistory(Market.KOSPI, 10).map { it.nets.foreign } shouldBe listOf(700L)
                service.dailyHistory(Market.KOSDAQ, 10).map { it.nets.foreign } shouldBe listOf(-300L)
            }
        }
    }

    private fun saveSnapshot(market: Market, date: LocalDate, time: LocalTime, foreign: Long) {
        repository.save(
            FuturesInvestorSnapshot(
                market = market,
                tradeDate = date,
                capturedAt = date.atTime(time),
                foreignQty = foreign,
                institutionQty = 0,
                individualQty = 0,
            ),
        )
    }

    companion object {
        private val DAY1 = LocalDate.of(2026, 7, 9)
        private val DAY2 = LocalDate.of(2026, 7, 10)
    }
}
