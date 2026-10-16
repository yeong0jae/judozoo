package judozoo.platform.vertex

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import judozoo.SharedWireMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class NewsArticlesTest {
    private val server = SharedWireMock.server.also { SharedWireMock.reset() }
    private val articles = NewsArticles(SimpleMeterRegistry())
    private val redirect = "${server.baseUrl()}/grounding-api-redirect/AAA"
    private val article = "${server.baseUrl()}/news/202609280920187609"

    @AfterEach
    fun 정리() = SharedWireMock.reset()

    private fun hop(from: String, to: String, status: Int = 302) {
        server.stubFor(get(urlEqualTo(from)).willReturn(aResponse().withStatus(status).withHeader("Location", to)))
    }

    private fun page(path: String, html: String, charset: String = "utf-8", headerCharset: Boolean = true) {
        server.stubFor(
            get(urlEqualTo(path)).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", if (headerCharset) "text/html; charset=$charset" else "text/html")
                    .withBody(html.toByteArray(Charset.forName(charset))),
            ),
        )
    }

    @Nested
    inner class 출처_따라가기 {
        @Test
        fun `전달 주소를 따라가 실제 URL과 제목을 얻는다`() {
            hop("/grounding-api-redirect/AAA", article)
            page("/news/202609280920187609", "<html><head><title>LG이노텍, 장 초반 10% 강세 - 파이낸셜뉴스</title></head></html>")

            val a = articles.resolve(redirect)!!

            a.url shouldBe article
            a.title shouldBe "LG이노텍, 장 초반 10% 강세 - 파이낸셜뉴스"
            a.site.shouldBeNull()
        }

        @Test
        fun `og title이 있으면 그것을 쓴다`() {
            hop("/grounding-api-redirect/AAA", article)
            page(
                "/news/202609280920187609",
                """<head><meta property="og:title" content="LG이노텍, 장 초반 10% 강세"><title>LG이노텍, 장 초반 10% 강세 : 파이낸셜뉴스 - 증권</title></head>""",
            )

            articles.resolve(redirect)!!.title shouldBe "LG이노텍, 장 초반 10% 강세"
        }

        @Test
        fun `매체명은 og site name에서 읽는다`() {
            hop("/grounding-api-redirect/AAA", article)
            page("/news/202609280920187609", """<head><meta property="og:site_name" content="파이낸셜뉴스"><title>LG이노텍, 장 초반 10% 강세</title></head>""")

            articles.resolve(redirect)!!.site shouldBe "파이낸셜뉴스"
        }

        @Test
        fun `헤더에 없어도 meta charset으로 EUC-KR을 읽는다`() {
            hop("/grounding-api-redirect/AAA", article)
            page(
                "/news/202609280920187609",
                """<head><meta charset="euc-kr"><title>대우건설, 용인 국가산단 낙찰</title></head>""",
                charset = "euc-kr", headerCharset = false,
            )

            articles.resolve(redirect)!!.title shouldBe "대우건설, 용인 국가산단 낙찰"
        }

        @Test
        fun `기사 쪽에서 한번 더 넘기면 마지막 주소를 남긴다`() {
            val final = "${server.baseUrl()}/m/news/202609280920187609"
            hop("/grounding-api-redirect/AAA", article)
            hop("/news/202609280920187609", final, status = 301)
            page("/m/news/202609280920187609", "<title>제목</title>")

            articles.resolve(redirect)!!.url shouldBe final
        }
    }

    @Nested
    inner class 못_따라가면_뺀다 {
        @Test
        fun `전달 주소가 넘기지 않으면 없다`() {
            server.stubFor(get(urlEqualTo("/grounding-api-redirect/AAA")).willReturn(aResponse().withStatus(404)))

            articles.resolve(redirect).shouldBeNull()
        }

        @Test
        fun `기사가 없어졌으면 없다`() {
            hop("/grounding-api-redirect/AAA", article)
            server.stubFor(get(urlEqualTo("/news/202609280920187609")).willReturn(aResponse().withStatus(404).withBody("없음")))

            articles.resolve(redirect).shouldBeNull()
        }

        @Test
        fun `제목이 없는 페이지는 없다`() {
            hop("/grounding-api-redirect/AAA", article)
            page("/news/202609280920187609", "<html><body>본문</body></html>")

            articles.resolve(redirect).shouldBeNull()
        }

        @Test
        fun `기사 사이트가 응답하지 않으면 없다`() {
            hop("/grounding-api-redirect/AAA", article)
            server.stubFor(get(urlEqualTo("/news/202609280920187609")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

            articles.resolve(redirect).shouldBeNull()
        }
    }
}
