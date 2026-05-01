package at.backend.platform.kis

import at.backend.platform.kis.config.KisProperties
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class KisRateLimiter(properties: KisProperties) {

    private val rateLimiter = RateLimiter.of(
        "kis",
        RateLimiterConfig.custom()
            .limitForPeriod(properties.rateLimitPerSecond)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofSeconds(10))
            .build()
    )

    fun acquire() {
        rateLimiter.acquirePermission()
    }
}
