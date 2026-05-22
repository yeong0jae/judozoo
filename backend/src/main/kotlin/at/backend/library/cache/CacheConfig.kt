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
 * - candidateStocks: 주도주 후보 리스트 — 프론트가 3초 폴링해도 키움 API를 직접 두드리지 않도록 5초 TTL
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
        return manager
    }
}
