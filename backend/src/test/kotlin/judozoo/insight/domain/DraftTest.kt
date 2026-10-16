package judozoo.insight.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DraftTest {
    private val sources = Sources(
        listOf(
            Source(1, "fnnews.com", "LG이노텍, 美라이다 기업 지분가치 상승 영향", "https://1"),
            Source(2, "daum.net", "LG이노텍, 美라이다 업체 베팅 결실", "https://2"),
            Source(3, "jkn.co.kr", "LG이노텍, 호재성 뉴스 부재 속 급등", "https://3"),
        ),
    )

    private fun draft(
        explained: Boolean = true,
        keywords: List<String> = listOf("Aeva", "라이다"),
        reason: String = "美 라이다 업체 Aeva 지분가치 상승",
        evidence: List<Int> = listOf(1, 2),
        related: List<Int> = listOf(3),
    ) = Draft(explained, keywords, reason, evidence, related)

    @Nested
    inner class 답_검사 {
        @Test
        fun `맞는 답은 그대로 게시한다`() {
            val v = draft().judge(sources)

            (v.published to v.explained) shouldBe (true to true)
            v.evidence.map { it.url } shouldBe listOf("https://1", "https://2")
            v.related.map { it.url } shouldBe listOf("https://3")
        }

        @Test
        fun `사유 끝의 마침표는 뗀다`() {
            draft(reason = "자사주 매입 추가 승인에 매수세 유입.").judge(sources).reason shouldBe "자사주 매입 추가 승인에 매수세 유입"
        }

        @Test
        fun `목록에 없는 번호를 대면 거절한다`() {
            val v = draft(evidence = listOf(1, 9)).judge(sources)

            (v.published to v.failure) shouldBe (false to Failure.BAD_ANSWER)
            v.error!! shouldContain "번호"
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "지분가치 상승, 지금 매수할 때", "지금 매수하세요", "지금 매도하세요", "이 종목 추천", "지금 담아", "지금 사야 한다",
                "지금 팔아", "Buy now", "Sell now", "증권사 목표가 상향, 지금 매수하세요",
            ],
        )
        fun `직접 투자를 권하면 설명 없음으로 내린다`(reason: String) {
            val v = draft(reason = reason).judge(sources)

            Triple(v.published, v.explained, v.error) shouldBe Triple(true, false, "권유 표현")
            v.reason shouldBe ""
            v.keywords.shouldBeEmpty()
            v.evidence.shouldBeEmpty()
        }

        @ParameterizedTest
        @ValueSource(strings = ["외국인이 매수하며 강세", "외국인이 대거 매수해서 반등", "외국인 매수했다", "기관이 팔아치운 물량 되사", "실적 기대를 담아낸 리포트"])
        fun `사고판 사실을 전하는 말은 권유가 아니다`(reason: String) {
            val v = draft(reason = reason).judge(sources)

            v.explained shouldBe true
            v.reason shouldBe reason
        }

        @ParameterizedTest
        @ValueSource(strings = ["증권사 목표가 상향에 상승", "증권가 목표주가 상향", "증권사 투자의견 상향에 매수세 유입", "증권사 매수 의견 유지", "증권사 매도 의견에서 중립으로 상향"])
        fun `증권사의 목표가와 투자의견 전달은 게시한다`(reason: String) {
            val v = draft(reason = reason, keywords = listOf("목표가 상향", "투자의견 상향")).judge(sources)

            (v.published to v.explained) shouldBe (true to true)
            v.reason shouldBe reason
            v.keywords shouldBe listOf("목표가 상향", "투자의견 상향")
        }

        @Test
        fun `키워드에서 직접 투자를 권해도 설명 없음으로 내린다`() {
            val v = draft(reason = "증권사 목표가 상향", keywords = listOf("매수 추천")).judge(sources)

            Triple(v.published, v.explained, v.error) shouldBe Triple(true, false, "권유 표현")
        }

        @Test
        fun `일반어 키워드는 그 칩만 뺀다`() {
            draft(keywords = listOf("상승", "라이다")).judge(sources).keywords shouldBe listOf("라이다")
        }

        @Test
        fun `종목 자신의 이름은 키워드가 아니다`() {
            draft(keywords = listOf("LG이노텍", "라이다")).judge(sources, stockName = "LG이노텍").keywords shouldBe listOf("라이다")
        }

        @Test
        fun `너무 긴 키워드는 뺀다`() {
            draft(keywords = listOf("미국 라이다 업체 지분가치", "Aeva")).judge(sources).keywords shouldBe listOf("Aeva")
        }

        @Test
        fun `근거 기사가 없는 사유는 설명 없음으로 내린다`() {
            val v = draft(evidence = emptyList(), related = listOf(3)).judge(sources)

            (v.published to v.explained) shouldBe (true to false)
            (v.reason to v.keywords) shouldBe ("" to emptyList())
            v.related.map { it.url } shouldBe listOf("https://3")
        }

        @Test
        fun `설명 없음이면 사유와 칩을 비운다`() {
            val v = draft(explained = false, reason = "뭔가", evidence = listOf(1)).judge(sources)

            v.explained shouldBe false
            (v.reason to v.keywords) shouldBe ("" to emptyList())
            v.evidence.shouldBeEmpty()
        }

        @Test
        fun `근거와 관련에 같은 기사가 있으면 근거에만 둔다`() {
            draft(evidence = listOf(1), related = listOf(1, 3)).judge(sources).related.map { it.id } shouldBe listOf(3)
        }
    }
}
