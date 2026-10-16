package judozoo.leadingstock.application

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import judozoo.library.KST
import judozoo.market.application.MarketCalendar
import judozoo.market.domain.Region
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 폴러가 언제 도는가 — 휴장·장 밖에는 브로커를 부르지 않고, 실패해도 다음 회차가 살아 있어야 한다. */
class LeadingStockSchedulerTest {
    private var now = LocalDateTime.of(2026, 9, 11, 10, 0)
    private var holiday = false
    private var tradingHours = true
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = KST
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = now.atZone(KST).toInstant()
    }
    private val calendar = mockk<MarketCalendar> {
        every { marketStatus() } answers { holiday to tradingHours }
        every { isHoliday(Region.KR) } answers { holiday }
    }
    private val service = mockk<LeadingStockService> {
        every { refreshTradingValuePool() } just runs
        every { syncTodayMinutes() } returns 0
    }
    private val stockSignals = mockk<StockSignalDetector> { every { detect() } just runs }
    private val marketFlows = mockk<MarketFlowDetector> { every { detect() } just runs }
    private val indexRebound = mockk<IndexReboundDetector> { every { detect() } just runs }
    private val scheduler = LeadingStockScheduler(service, stockSignals, marketFlows, indexRebound, calendar, clock)

    private fun runAll() {
        scheduler.refreshTradingValuePool()
        scheduler.syncTodayMinutes()
        scheduler.detectStockSignals()
        scheduler.detectMarketFlows()
        scheduler.detectIndexRebound()
    }

    @Nested
    inner class 장중 {
        @Test
        fun `정규장이면 모든 폴러가 돈다`() {
            runAll()

            verify(exactly = 1) { service.refreshTradingValuePool() }
            verify(exactly = 1) { service.syncTodayMinutes() }
            verify(exactly = 1) { stockSignals.detect() }
            verify(exactly = 1) { marketFlows.detect() }
            verify(exactly = 1) { indexRebound.detect() }
        }

        @Test
        fun `실패해도 폴러가 죽지 않는다`() {
            every { service.refreshTradingValuePool() } throws IllegalStateException("키움 오류")
            every { service.syncTodayMinutes() } throws IllegalStateException("후보 조회 실패")
            every { stockSignals.detect() } throws IllegalStateException("DB 오류")
            every { marketFlows.detect() } throws IllegalStateException("키움 오류")
            every { indexRebound.detect() } throws IllegalStateException("키움 오류")

            runAll()
        }
    }

    @Nested
    inner class 쉬는_때 {
        @Test
        fun `휴장이면 아무것도 돌지 않는다`() {
            holiday = true

            runAll()

            verify(exactly = 0) { service.refreshTradingValuePool() }
            verify(exactly = 0) { service.syncTodayMinutes() }
            verify(exactly = 0) { stockSignals.detect() }
            verify(exactly = 0) { marketFlows.detect() }
            verify(exactly = 0) { indexRebound.detect() }
        }

        @Test
        fun `장 시간이 아니면 후보 풀·분봉·종목 시그널이 돌지 않는다`() {
            tradingHours = false
            now = LocalDateTime.of(2026, 9, 11, 21, 0)

            runAll()

            verify(exactly = 0) { service.refreshTradingValuePool() }
            verify(exactly = 0) { service.syncTodayMinutes() }
            verify(exactly = 0) { stockSignals.detect() }
            verify(exactly = 0) { marketFlows.detect() }
        }

        /** 시황의 세션 표가 프리·애프터 구간을 스냅샷 경계 차이로 만든다 — 정규장 밖 누적도 남겨야 한다. */
        @Test
        fun `시장 수급은 프리마켓에도 돌지만 지수 반등은 정규장만 돈다`() {
            now = LocalDateTime.of(2026, 9, 11, 8, 30)

            runAll()

            verify(exactly = 1) { marketFlows.detect() }
            verify(exactly = 0) { indexRebound.detect() }
        }

        @Test
        fun `정규장 마감 15시 30분까지는 지수 반등이 돈다`() {
            now = LocalDateTime.of(2026, 9, 11, 15, 30)
            scheduler.detectIndexRebound()
            now = LocalDateTime.of(2026, 9, 11, 15, 31)
            scheduler.detectIndexRebound()

            verify(exactly = 1) { indexRebound.detect() }
        }
    }
}
