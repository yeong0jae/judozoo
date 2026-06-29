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
     * 분봉 페이징·랭킹·일봉 버스트가 EGW00201(초당 거래건수 초과)을 내지 않게 평탄화.
     * yaml `kis.query.permits-per-second`로 조정(기본 10 — 한도 20의 절반.
     * 리미터 1초 윈도우와 KIS 윈도우가 어긋나 경계에서 2배까지 몰릴 수 있어 절반으로 둔다).
     */
    @Bean
    fun kisRateLimiter(
        @Value("\${kis.query.permits-per-second:10}") permitsPerSecond: Int,
    ): RateLimiter =
        RateLimiter.of(
            "kis-query",
            RateLimiterConfig.custom()
                .limitForPeriod(permitsPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(20)) // 버스트 시 거부 대신 대기
                .build(),
        )

    @Bean
    fun kisRestClient(properties: KisApiProperties, kisRateLimiter: RateLimiter): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestInterceptor(KisRateLimitInterceptor(kisRateLimiter))
            .build()
}
