package at.backend.library.cache

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * Caffeine 기반 인메모리 캐시.
 *
 * - 전역 기본: 60초 TTL, 최대 100개 엔트리
 * - candidateStocks: 주도주 후보 리스트 — 5초 TTL, 단일 엔트리
 * - topTradingValueStocks: 거래대금 상위 raw 리스트 — 5초 TTL, 단일 엔트리
 *     (후보 폴링과 상세 평가가 동일 응답 공유 → Kiwoom 호출/rate limit 압력 ↓)
 * - kospiIndex: KOSPI 종합지수 — 5초 TTL, 단일 엔트리
 *
 * 캐시별 maximumSize=1 이유: 각 캐시가 한 종류 결과만 담는 단일 슬롯.
 * 다른 서비스가 같은 캐시 이름을 공유하면 서로 갈아치우므로 캐시별 분리 필수.
 */
@Configuration
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager().apply {
            setCaffeine(
                Caffeine.newBuilder()
                    .expireAfterWrite(60, TimeUnit.SECONDS)
                    .maximumSize(100),
            )
        }
        manager.registerCustomCache(
            "candidateStocks",
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .maximumSize(1)
                .build(),
        )
        manager.registerCustomCache(
            "topTradingValueStocks",
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .maximumSize(1)
                .build(),
        )
        manager.registerCustomCache(
            "kospiIndex",
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .maximumSize(1)
                .build(),
        )
        return manager
    }
}
