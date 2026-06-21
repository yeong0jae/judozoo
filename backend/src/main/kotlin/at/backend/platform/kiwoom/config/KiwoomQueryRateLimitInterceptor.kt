package at.backend.platform.kiwoom.config

import io.github.resilience4j.ratelimiter.RateLimiter
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * 키움 "조회 초당 5건" 한도를 모든 조회 호출에 한 버킷으로 묶어 강제한다.
 * 조회 TR은 api-id가 `ka`로 시작(ka10080 분봉, ka10032 거래대금 상위 등) — 그 요청만 퍼밋을 받게 하고,
 * 주문/계좌(kt*)는 별도 한도라 통과시킨다. 폴러·돌파·스파이크가 같은 종목 분봉을 몰아 부를 때의 버스트를
 * 여기서 평탄화한다(퍼밋 못 받으면 timeout까지 대기).
 */
class KiwoomQueryRateLimitInterceptor(
    private val rateLimiter: RateLimiter,
) : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        if (request.headers.getFirst(API_ID_HEADER)?.startsWith(QUERY_PREFIX) == true) {
            rateLimiter.acquirePermission()
        }
        return execution.execute(request, body)
    }

    companion object {
        private const val API_ID_HEADER = "api-id"
        private const val QUERY_PREFIX = "ka"
    }
}
