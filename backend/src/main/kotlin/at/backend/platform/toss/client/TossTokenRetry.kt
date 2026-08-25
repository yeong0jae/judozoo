package at.backend.platform.toss.client

import org.slf4j.Logger
import org.springframework.web.client.HttpClientErrorException

/**
 * 토스 응답이 401이면 토큰 캐시를 무효화하고 1회 재시도.
 *
 * 토스는 client당 유효 토큰이 1개라, 같은 client_id로 다른 인스턴스가 토큰을 발급받으면
 * 이쪽 토큰이 즉시 무효화된다. 이때 만료 시각은 아직 한참 남아 있어 죽은 토큰을 계속 쓰게 되고,
 * 재시작 전까지 복구되지 않는다(키움 `withKiwoomTokenRetry`와 같은 문제, 같은 대응).
 *
 * [block] 안에서 `getAccessToken()`을 호출해야 재시도 시 새 토큰을 받는다.
 */
internal inline fun <T> TossAuthClient.withTokenRetry(log: Logger, block: () -> T): T = try {
    block()
} catch (e: HttpClientErrorException.Unauthorized) {
    log.warn("Toss 토큰 무효(401) 감지 — 캐시 무효화 후 1회 재시도")
    invalidate()
    block()
}
