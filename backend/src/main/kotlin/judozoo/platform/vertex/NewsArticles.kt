package judozoo.platform.vertex

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.web.MeteredInterceptor
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.Charset
import java.time.Duration

/**
 * 그라운딩 출처를 실제 기사로 — 전달 주소를 따라가 기사 URL과 페이지 제목을 얻는다.
 *
 * Vertex가 주는 출처는 `vertexaisearch.cloud.google.com/grounding-api-redirect/…` 전달 주소와 도메인뿐이다.
 * 화면에는 원문 링크와 기사 제목이 필요하고, 모델이 쓴 URL은 지어낸 것이라(026 §0) 서버가 직접 확인한다.
 * 제목 다듬기(매체명·사이트 경로 떼기)는 insight 도메인이 한다.
 */
@Component
class NewsArticles(registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [site]는 매체명(`og:site_name`). 없으면 null — 부르는 쪽이 도메인으로 대신한다. */
    data class Article(val url: String, val title: String, val site: String? = null)

    // 리다이렉트는 직접 따라간다 — 마지막 주소를 알아야 한다
    private val rest = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10)).build(),
            ).apply { setReadTimeout(Duration.ofSeconds(10)) },
        )
        .defaultHeader("User-Agent", "Mozilla/5.0")
        .requestInterceptor(MeteredInterceptor("news", registry) { it.uri.host.orEmpty() })
        .build()

    private class Page(val status: Int, val location: String?, val charset: Charset?, val head: ByteArray)

    /** 전달 주소 → (실제 URL, 페이지 제목). 어느 단계든 실패하면 null — 그 출처는 목록에서 빠진다. */
    fun resolve(redirectUri: String): Article? {
        try {
            var url = fetch(redirectUri).location ?: return null
            repeat(MAX_REDIRECTS) {
                val page = fetch(url)
                if (page.status in 300..399 && page.location != null) {
                    url = URI(url).resolve(page.location).toString()
                    return@repeat
                }
                if (page.status != 200) return null
                return toArticle(url, page)
            }
            return null
        } catch (e: Exception) {
            log.info("출처를 따라가지 못함 {}", redirectUri.take(80), e)
            return null
        }
    }

    private fun fetch(url: String): Page = rest.get().uri(URI(url)).exchange { _, response ->
        val head = response.body.use { it.readNBytes(HEAD_BYTES) } // 제목은 문서 앞쪽에 있다 — 본문 전체를 받지 않는다
        Page(response.statusCode.value(), response.headers.location?.toString(), response.headers.contentType?.charset, head)
    }

    private fun toArticle(url: String, page: Page): Article? {
        // 정규식은 바이트 그대로 본다(Latin-1은 바이트를 1:1로 옮긴다). 찾은 조각만 페이지 인코딩으로 푼다
        val raw = String(page.head, Charsets.ISO_8859_1)
        val encoding = page.charset ?: META_CHARSET.find(raw)?.groupValues?.get(1)?.let(::charsetOrNull) ?: Charsets.UTF_8
        // og:title이 먼저다 — <title>보다 매체명이 덜 붙어 있다
        val title = decode(OG_TITLE.find(raw)?.groupValues?.get(1) ?: TITLE.find(raw)?.groupValues?.get(1), encoding)
        val site = decode(OG_SITE.find(raw)?.groupValues?.get(1), encoding)
        return if (title.isEmpty()) null else Article(url, title, site.ifEmpty { null })
    }

    companion object {
        private const val HEAD_BYTES = 200_000
        private const val MAX_REDIRECTS = 10

        private val OG_TITLE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
        private val OG_SITE = Regex("""<meta[^>]+property=["']og:site_name["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val META_CHARSET = Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)

        private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

        private fun decode(latin1: String?, encoding: Charset): String =
            latin1?.toByteArray(Charsets.ISO_8859_1)?.toString(encoding)?.trim().orEmpty()
    }
}
