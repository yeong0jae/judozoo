package at.backend.platform.kis.config

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class KisRestClientConfig {

    /**
     * KIS 호출 공유 리미터 — 한도 "초당 N건"을 모든 KIS 호출에 한 버킷으로 적용.
     * 1초에 N개를 한꺼번에 충전하면 1초 경계에서 이전·이후 창이 KIS 윈도우에 겹쳐 최대 2N이 몰려
     * EGW00201이 간헐 발생한다. 그래서 (1000/N)ms마다 1개씩 **균등** 발급해 버스트를 없앤다.
     * yaml `kis.query.permits-per-second`로 조정(기본 10).
     */
    @Bean
    fun kisRateLimiter(
        @Value("\${kis.query.permits-per-second:10}") permitsPerSecond: Int,
    ): RateLimiter =
        RateLimiter.of(
            "kis-query",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(1000L / permitsPerSecond))
                .timeoutDuration(Duration.ofSeconds(30)) // 페이징·동시요청이 큐잉돼도 거부 대신 대기
                .build(),
        )

    @Bean
    fun kisRestClient(properties: KisApiProperties, kisRateLimiter: RateLimiter): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestInterceptor(KisRateLimitInterceptor(kisRateLimiter))
            .build()
}
