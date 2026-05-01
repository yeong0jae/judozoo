package at.backend.platform.kis

import at.backend.platform.kis.config.KisProperties
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import kotlin.system.measureTimeMillis

class KisRateLimiterTest : FunSpec({

    fun rateLimiter(perSecond: Int) = KisRateLimiter(
        KisProperties(
            appKey = "key",
            appSecret = "secret",
            accountNo = "00000000",
            accountProductCode = "01",
            baseUrl = "http://localhost",
            wsUrl = "ws://localhost",
            rateLimitPerSecond = perSecond,
        )
    )

    context("제한 이내 호출") {
        test("모든 요청이 즉시 통과") {
            val limiter = rateLimiter(perSecond = 5)
            val elapsed = measureTimeMillis {
                repeat(5) { limiter.acquire() }
            }
            elapsed shouldBeLessThan 200L
        }
    }

    context("제한 초과 호출") {
        test("초과 요청은 다음 윈도우까지 대기 후 완료") {
            val limiter = rateLimiter(perSecond = 2)
            val elapsed = measureTimeMillis {
                repeat(3) { limiter.acquire() }
            }
            // 3rd call had to wait for the next 1-second window
            elapsed shouldBeGreaterThanOrEqual 800L
        }
    }
})
