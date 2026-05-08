package at.backend.trading.infrastructure.scheduler

import at.backend.trading.application.CycleOrchestrator
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify

class MarketCloseSchedulerTest : FunSpec({

    context("15:20 강제 청산") {
        test("활성 사이클에 MarketClose 시그널을 일제 라우팅한다") {
            val orchestrator = mockk<CycleOrchestrator>(relaxed = true)
            val scheduler = MarketCloseScheduler(orchestrator)

            scheduler.routeMarketCloseSignal()

            verify(exactly = 1) { orchestrator.broadcastMarketClose() }
        }
    }
})
