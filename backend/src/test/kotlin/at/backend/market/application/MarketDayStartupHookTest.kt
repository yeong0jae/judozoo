package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.market.domain.event.HolidayChanged
import at.backend.trading.application.broker.BrokerTradingClient
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalDateTime

class MarketDayStartupHookTest : FunSpec({

    fun newHook(): Triple<MarketDayStartupHook, BrokerTradingClient, ApplicationEventPublisher> {
        val broker = mockk<BrokerTradingClient>()
        val time = mockk<TimeProvider>().also {
            every { it.today() } returns LocalDate.of(2026, 5, 4)
            every { it.now() } returns LocalDateTime.of(2026, 5, 4, 8, 30)
        }
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        return Triple(MarketDayStartupHook(broker, time, publisher), broker, publisher)
    }

    context("부팅 시 영업일 상태 발행") {
        test("영업일이면 HolidayChanged(false) 발행") {
            val (hook, broker, publisher) = newHook()
            every { broker.isMarketOpen(any()) } returns true

            hook.publishMarketDay()

            verify(exactly = 1) {
                publisher.publishEvent(match<HolidayChanged> { !it.isHoliday })
            }
        }

        test("휴장일이면 HolidayChanged(true) 발행") {
            val (hook, broker, publisher) = newHook()
            every { broker.isMarketOpen(any()) } returns false

            hook.publishMarketDay()

            verify(exactly = 1) {
                publisher.publishEvent(match<HolidayChanged> { it.isHoliday })
            }
        }

        test("broker 호출 실패 시 이벤트 미발행") {
            val (hook, broker, publisher) = newHook()
            every { broker.isMarketOpen(any()) } throws RuntimeException("network down")

            hook.publishMarketDay()

            verify(exactly = 0) { publisher.publishEvent(any<HolidayChanged>()) }
        }
    }
})
