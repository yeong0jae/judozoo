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
 * - candidateStocks: 주도주 후보 리스트 — 5초 TTL, 등락률 임계값(-12~7%)별 엔트리
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
                .maximumSize(15) // 등락률 임계값 -12~7%별 슬롯
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
            "stockDetail", // 종목 기본정보(ka10001) — 상세 평가에서 후보별 호출
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .maximumSize(60)
                .build(),
        )
        manager.registerCustomCache(
            "minuteCandles", // 분봉(ka10080) — 돌파 레이더가 후보별로 호출, 상세 시그널과 공유
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(60)
                .build(),
        )
        manager.registerCustomCache(
            "kospiIndex",
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .maximumSize(1)
                .build(),
        )
        manager.registerCustomCache(
            "kosdaqIndex",
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .maximumSize(1)
                .build(),
        )
        manager.registerCustomCache(
            "stockThemes", // 종목별 테마명 — 하루 단위로도 거의 불변, 12시간 TTL
            Caffeine.newBuilder()
                .expireAfterWrite(12, TimeUnit.HOURS)
                .maximumSize(200)
                .build(),
        )
        manager.registerCustomCache(
            "minuteCandlesHistory", // 과거 거래일 분봉(ka10080) — 마감돼 불변, 차트 다일치용 장기 TTL
            Caffeine.newBuilder()
                .expireAfterWrite(12, TimeUnit.HOURS)
                .maximumSize(300)
                .build(),
        )
        manager.registerCustomCache(
            "dailyCandles", // 일봉(ka10081) — 상세 필터/RVOL·일봉 차트 공유. 당일 봉 변동 반영해 30s
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(60)
                .build(),
        )
        manager.registerCustomCache(
            "kisOverseasRanking", // KIS 해외주식 거래대금순위 — 거래소별(NYS/NAS/AMS) 슬롯, 15s TTL
            Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.SECONDS)
                .maximumSize(3)
                .build(),
        )
        manager.registerCustomCache(
            "sectorNetBuy", // 코스피/코스닥 투자자 순매수 — 폴러(2분)·상세 패널 공유, 시장별 슬롯 30s
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(2)
                .build(),
        )
        manager.registerCustomCache(
            "kisOverseasMarketCap", // KIS 해외 시가총액 — 종목별, 상장주식수 거의 불변이라 1h
            Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(100)
                .build(),
        )
        manager.registerCustomCache(
            "kisOverseasMinuteCandles", // KIS 해외 분봉 — 종목별 2거래일 통째. 1분봉이라 60s면 충분
            Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(60)
                .build(),
        )
        manager.registerCustomCache(
            "kisOverseasDailyCandles", // KIS 해외 일봉 — 종목별, 당일 봉 변동 반영 30s
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(60)
                .build(),
        )
        return manager
    }
}
