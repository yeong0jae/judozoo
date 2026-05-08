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
            tr = KisProperties.Tr("BUY", "SELL", "CANCEL", "BAL", "EXEC"),
            marketDivCode = "UN",
            exchangeId = "SOR",
        )
    )

    context("균등 분배 정책 (1초/N 간격)") {
        test("첫 호출은 즉시 통과") {
            val limiter = rateLimiter(perSecond = 5)
            val elapsed = measureTimeMillis { limiter.acquire() }
            elapsed shouldBeLessThan 100L
        }

        test("perSecond=5이면 5건 acquire에 ~800ms (4 * 200ms 간격)") {
            val limiter = rateLimiter(perSecond = 5)
            val elapsed = measureTimeMillis {
                repeat(5) { limiter.acquire() }
            }
            elapsed shouldBeGreaterThanOrEqual 700L
            elapsed shouldBeLessThan 1200L
        }

        test("burst 거부 — 동시 호출이 균등 간격으로 직렬화") {
            val limiter = rateLimiter(perSecond = 2) // 500ms 간격
            val elapsed = measureTimeMillis {
                repeat(3) { limiter.acquire() }
            }
            // 첫 호출 0ms, 두번째 ~500ms, 세번째 ~1000ms → 총 ~1000ms 이상
            elapsed shouldBeGreaterThanOrEqual 900L
        }
    }
})
