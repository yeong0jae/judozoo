package at.backend.market.application

import at.backend.library.time.TimeProvider
import at.backend.market.domain.event.HolidayChanged
import at.backend.platform.kis.client.KisRestClient
import at.backend.platform.kis.client.response.KisHolidayResponse
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalDateTime

class MarketDayStartupHookTest : FunSpec({

    fun newHook(): Triple<MarketDayStartupHook, KisRestClient, ApplicationEventPublisher> {
        val kis = mockk<KisRestClient>()
        val time = mockk<TimeProvider>().also {
            every { it.today() } returns LocalDate.of(2026, 5, 4)
            every { it.now() } returns LocalDateTime.of(2026, 5, 4, 8, 30)
        }
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        return Triple(MarketDayStartupHook(kis, time, publisher), kis, publisher)
    }

    context("부팅 시 영업일 상태 발행") {
        test("영업일이면 HolidayChanged(false) 발행") {
            val (hook, kis, publisher) = newHook()
            every { kis.checkHoliday(any()) } returns KisHolidayResponse(
                output = listOf(KisHolidayResponse.Output(opndYn = "Y"))
            )

            hook.publishMarketDay()

            verify(exactly = 1) {
                publisher.publishEvent(match<HolidayChanged> { !it.isHoliday })
            }
        }

        test("휴장일이면 HolidayChanged(true) 발행") {
            val (hook, kis, publisher) = newHook()
            every { kis.checkHoliday(any()) } returns KisHolidayResponse(
                output = listOf(KisHolidayResponse.Output(opndYn = "N"))
            )

            hook.publishMarketDay()

            verify(exactly = 1) {
                publisher.publishEvent(match<HolidayChanged> { it.isHoliday })
            }
        }

        test("KIS 호출 실패 시 이벤트 미발행") {
            val (hook, kis, publisher) = newHook()
            every { kis.checkHoliday(any()) } throws RuntimeException("network down")

            hook.publishMarketDay()

            verify(exactly = 0) { publisher.publishEvent(any<HolidayChanged>()) }
        }
    }
})
