package at.backend.platform.kiwoom.config

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class KiwoomRestClientConfig {

    /**
     * 키움 조회(ka*) 호출 공유 리미터 — 한도 "초당 5건"을 모든 조회 호출(폴러·돌파·스파이크·상세)에 한 버킷으로 적용.
     * permits 기본 5, 한도에 딱 붙이면 위험하니 yaml `kiwoom.query.permits-per-second`로 4 등으로 낮춰 여유를 둘 수 있다.
     */
    @Bean
    fun kiwoomQueryRateLimiter(
        @Value("\${kiwoom.query.permits-per-second:5}") permitsPerSecond: Int,
    ): RateLimiter =
        RateLimiter.of(
            "kiwoom-query",
            RateLimiterConfig.custom()
                .limitForPeriod(permitsPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(20)) // 버스트 시 거부 대신 대기
                .build(),
        )

    @Bean
    fun kiwoomRestClient(
        properties: KiwoomApiProperties,
        kiwoomQueryRateLimiter: RateLimiter,
    ): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestInterceptor(KiwoomQueryRateLimitInterceptor(kiwoomQueryRateLimiter))
            .build()
}
