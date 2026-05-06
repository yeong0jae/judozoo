package at.backend.platform.kis

import at.backend.platform.kis.config.KisProperties
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * KIS REST 호출 토큰 버킷.
 *
 * 단순히 "초당 N건" (limitForPeriod=N, refresh=1s)으로 설정하면 1초의 첫 ms에 N건이
 * burst로 발송되어 KIS가 EGW00201 ("초당 거래건수 초과")로 거부할 수 있음 (KIS는 sliding
 * window 기준). 따라서 `1초/N` 간격으로 균등 분배되도록 refresh period를 잘게 쪼갠다.
 */
@Component
class KisRateLimiter(properties: KisProperties) {

    private val rateLimiter = run {
        val perSecond = properties.rateLimitPerSecond.coerceAtLeast(1)
        val intervalMillis = (1000L / perSecond).coerceAtLeast(1)
        RateLimiter.of(
            "kis",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(intervalMillis))
                .timeoutDuration(Duration.ofSeconds(10))
                .build(),
        )
    }

    fun acquire() {
        rateLimiter.acquirePermission()
    }
}
