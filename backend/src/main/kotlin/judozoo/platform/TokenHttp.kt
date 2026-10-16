package judozoo.platform

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.web.MeteredInterceptor
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * 토큰 발급용 HTTP. 조회 리미터를 타지 않는다 — 발급은 조회 한도에 들지 않는다.
 * **리다이렉트를 따라가지 않는다** — 키움은 거부할 때 `start.html`로 302를 준다. 따라가면 거부가 200으로 보인다.
 * 메트릭은 `vendor="<브로커>-token"`으로 따로 센다 — 조회 클라이언트와 같은 이름이면 동시 요청 게이지가 겹쳐 등록되지 않는다.
 */
object TokenHttp {
    fun client(baseUrl: String, vendor: String, registry: MeterRegistry): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
        )
        .requestInterceptor(MeteredInterceptor("$vendor-token", registry) { it.uri.path })
        .build()
}
