package at.backend.trading.infrastructure.scheduler

import at.backend.library.time.TimeProvider
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisHolidayResponse
import at.backend.trading.application.CommandGate
import at.backend.trading.application.CycleOrchestrator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime

class TradingSchedulerServiceTest : FunSpec({

    fun newScheduler(): Triple<TradingSchedulerService, CommandGate, CycleOrchestrator> {
        val orchestrator = mockk<CycleOrchestrator>(relaxed = true)
        val gate = CommandGate()
        val kis = mockk<KisRestClient>(relaxed = true)
        val time = mockk<TimeProvider>().also {
            every { it.now() } returns LocalDateTime.of(2026, 5, 4, 8, 0)
        }
        val scheduler = TradingSchedulerService(orchestrator, gate, kis, time, mockk(relaxed = true))
        return Triple(scheduler, gate, orchestrator).also { _ ->
            every { kis.checkHoliday(any()) } returns KisHolidayResponse(
                output = listOf(KisHolidayResponse.Output(bzdyYn = "Y"))
            )
        }
    }

    context("15:20 강제 청산") {
        test("활성 사이클에 MarketClose 시그널을 일제 라우팅한다") {
            val (scheduler, _, orchestrator) = newScheduler()

            scheduler.routeMarketCloseSignal()

            verify(exactly = 1) { orchestrator.broadcastMarketClose() }
        }
    }

    context("08:00 게이트 토글") {
        test("영업일이면 게이트가 OPEN으로 토글된다") {
            val (scheduler, gate, _) = newScheduler()
            gate.close()

            scheduler.toggleCommandGate()

            gate.isOpen() shouldBe true
        }

        test("휴장일이면 게이트가 CLOSED로 토글된다") {
            val orchestrator = mockk<CycleOrchestrator>(relaxed = true)
            val gate = CommandGate()
            val kis = mockk<KisRestClient>(relaxed = true)
            val time = mockk<TimeProvider>().also {
                every { it.now() } returns LocalDateTime.of(2026, 5, 4, 8, 0)
            }
            every { kis.checkHoliday(any()) } returns KisHolidayResponse(
                output = listOf(KisHolidayResponse.Output(bzdyYn = "N"))
            )
            val scheduler = TradingSchedulerService(orchestrator, gate, kis, time, mockk(relaxed = true))

            scheduler.toggleCommandGate()

            gate.isOpen() shouldBe false
        }

        test("KIS 호출 실패 시 게이트는 CLOSED로 닫힌다") {
            val orchestrator = mockk<CycleOrchestrator>(relaxed = true)
            val gate = CommandGate()
            val kis = mockk<KisRestClient>(relaxed = true)
            val time = mockk<TimeProvider>().also {
                every { it.now() } returns LocalDateTime.of(2026, 5, 4, 8, 0)
            }
            every { kis.checkHoliday(any()) } throws RuntimeException("network down")
            val scheduler = TradingSchedulerService(orchestrator, gate, kis, time, mockk(relaxed = true))

            scheduler.toggleCommandGate()

            gate.isOpen() shouldBe false
        }
    }
})
