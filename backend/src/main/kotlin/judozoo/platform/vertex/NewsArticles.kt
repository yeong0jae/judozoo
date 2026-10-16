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

@Component
class NewsArticles(registry: MeterRegistry, restClient: RestClient.Builder = RestClient.builder()) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Article(val url: String, val title: String, val site: String? = null)

    private val rest = restClient.clone()
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
        val head = response.body.use { it.readNBytes(HEAD_BYTES) }
        Page(response.statusCode.value(), response.headers.location?.toString(), response.headers.contentType?.charset, head)
    }

    private fun toArticle(url: String, page: Page): Article? {
        val raw = String(page.head, Charsets.ISO_8859_1)
        val encoding = page.charset ?: META_CHARSET.find(raw)?.groupValues?.get(1)?.let(::charsetOrNull) ?: Charsets.UTF_8
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
