package at.backend.platform.yahoo.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * 야후 파이낸스 chart API — 인증 없는 공개 엔드포인트.
 * KIS는 CME 시세가 유료라 나스닥 선물을 못 받는다. 그 대체 소스.
 * User-Agent가 없으면 차단되므로 반드시 붙인다.
 */
@Configuration
class YahooRestClientConfig {

    @Bean
    fun yahooRestClient(): RestClient =
        RestClient.builder()
            .baseUrl("https://query1.finance.yahoo.com")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build()
}
