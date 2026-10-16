package judozoo.insight.domain

import io.kotest.matchers.shouldBe
import judozoo.insight.domain.Sources.Raw
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SourcesTest {
    @Nested
    inner class 기사_제목_정리 {
        @ParameterizedTest
        @CsvSource(
            delimiter = '|',
            value = [
                "LG이노텍, 장 초반 10% 강세 [종목 NOW] - 파이낸셜뉴스|LG이노텍, 장 초반 10% 강세 [종목 NOW]",
                "코리아써키트 7만원 돌파 < 증시 < 금융 < 경제 < 기사본문 - 잡포스트|코리아써키트 7만원 돌파",
                "Bloom Energy Stock Surges As S&amp;P 500 Deals Align|Bloom Energy Stock Surges As S&P 500 Deals Align",
            ],
        )
        fun `매체명 사이트 경로 엔티티를 걷어낸다`(raw: String, title: String) {
            Sources.cleanTitle(raw) shouldBe title
        }

        @Test
        fun `세로줄 뒤 매체명도 걷어낸다`() {
            Sources.cleanTitle("엔비디아가 콕 집었다…LG전자 껑충 | 한국경제") shouldBe "엔비디아가 콕 집었다…LG전자 껑충"
        }
    }

    @Nested
    inner class 출처_목록 {
        @Test
        fun `시세 중계 기사는 번호를 받지 못한다`() {
            val sources = Sources.of(
                listOf(
                    Raw("fnnews.com", "LG이노텍, 美라이다 기업 지분가치 상승 영향", "https://a"),
                    Raw("topstarnews.net", "LG이노텍 주가, 9월 28일 장중 579,000원 8.22% 상승", "https://b"),
                    Raw("247wallst.com", "Apple (AAPL) Sets a New 52-Week High at $341.07", "https://c"),
                    Raw("gurufocus.com", "Tech Stocks Surge as Apple (AAPL) Hits Record High", "https://d"),
                ),
            )

            sources.map { it.url } shouldBe listOf("https://a")
        }

        @Test
        fun `같은 기사는 한 번만 싣는다`() {
            Sources.of(listOf(Raw("a.com", "같은 기사의 제목입니다", "https://x"), Raw("a.com", "같은 기사의 제목입니다", "https://x"))).size shouldBe 1
        }

        @Test
        fun `기사가 아닌 사이트 제목은 뺀다`() {
            Sources.of(listOf(Raw("alphasquare.co.kr", "알파스퀘어", "https://home"), Raw("daum.net", "대우건설, 용인 반도체 국가산단 1공구 공사 따냈다", "https://a")))
                .map { it.url } shouldBe listOf("https://a")
        }

        @Test
        fun `포털을 거친 매체명은 원래 매체만 남긴다`() {
            Sources.of(listOf(Raw("Daum | 서울경제", "대우건설, 용인 반도체 국가산단 1공구 공사 따냈다", "https://a"))).items[0].domain shouldBe "서울경제"
        }

        @Test
        fun `번호는 1부터 차례로 붙는다`() {
            Sources.of(listOf(Raw("a.com", "첫 번째 기사 제목입니다", "https://1"), Raw("b.com", "두 번째 기사 제목입니다", "https://2"))).map { it.id } shouldBe listOf(1, 2)
        }
    }
}
