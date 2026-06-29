package at.backend.platform.kis.config

import io.github.resilience4j.ratelimiter.RateLimiter
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * KIS "초당 거래건수"(EGW00201) 한도를 모든 호출에 한 버킷으로 강제한다.
 * 분봉 페이징(KEYB 다음조회 다회)·거래소별 랭킹·일봉이 한 화면에서 몰릴 때의 버스트를
 * 여기서 평탄화한다(퍼밋 못 받으면 timeout까지 대기).
 */
class KisRateLimitInterceptor(
    private val rateLimiter: RateLimiter,
) : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        rateLimiter.acquirePermission()
        return execution.execute(request, body)
    }
}
