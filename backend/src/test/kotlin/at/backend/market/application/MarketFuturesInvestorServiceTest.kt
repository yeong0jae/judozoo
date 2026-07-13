package at.backend.market.application

import at.backend.common.test.IntegrationTestBase
import at.backend.market.domain.FuturesInvestorSnapshot
import at.backend.market.infrastructure.repository.FuturesInvestorSnapshotRepository
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
                saveSnapshot(DAY1, LocalTime.of(9, 0), foreign = 100)
                saveSnapshot(DAY1, LocalTime.of(15, 45), foreign = 700)
                saveSnapshot(DAY2, LocalTime.of(12, 0), foreign = 200)
                saveSnapshot(DAY2, LocalTime.of(15, 40), foreign = -500)

                val days = service.dailyHistory(10)

                days.map { it.date } shouldBe listOf(DAY2, DAY1)
                days.map { it.nets.foreign } shouldBe listOf(-500L, 700L)
            }

            test("요청한 일수만큼만 최근 거래일을 준다") {
                saveSnapshot(DAY1, LocalTime.of(15, 45), foreign = 700)
                saveSnapshot(DAY2, LocalTime.of(15, 45), foreign = -500)

                val days = service.dailyHistory(1)

                days.map { it.date } shouldBe listOf(DAY2)
            }

            test("스냅샷이 하나도 없으면 빈 목록") {
                service.dailyHistory(10) shouldBe emptyList()
            }
        }
    }

    private fun saveSnapshot(date: LocalDate, time: LocalTime, foreign: Long) {
        repository.save(
            FuturesInvestorSnapshot(
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
