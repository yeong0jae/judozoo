package judozoo.insight.domain

import tools.jackson.databind.JsonNode

/** 2차 호출이 돌려준 답 그대로. [judge]가 게시할지, 무엇을 게시할지 정한다. */
data class Draft(val explained: Boolean, val keywords: List<String>, val reason: String, val evidence: List<Int>, val related: List<Int>) {

    /** 게시할지, 무엇을 게시할지. 거절은 행으로 남되 화면에는 이전 사유가 그대로 보인다. */
    fun judge(sources: Sources, stockName: String = ""): Verdict {
        val unknown = (evidence + related).filter { sources[it] == null }
        if (unknown.isNotEmpty()) return Verdict(published = false, error = "목록에 없는 기사 번호 $unknown", failure = Failure.BAD_ANSWER)

        val evidenceIds = evidence.distinct().take(MAX_ARTICLES)
        val relatedIds = related.distinct().filterNot { it in evidenceIds }.take(MAX_ARTICLES)
        fun toSources(ids: List<Int>) = ids.mapNotNull { sources[it] }

        // 목록 한 줄은 명사형으로 끊는다("~ 매수세 유입"). 끝 마침표는 코드로 떼고, "~다" 말투는 프롬프트가 막는다
        val cleaned = reason.trim().trimEnd('.', '。').trim()
        // 근거 없는 사유는 믿지 않는다 — 모델은 설명 없음을 잘 쓰지 않는다(026 §알려진 함정).
        // 권유 표현도 설명 없음으로 내린다 — 근거 기사가 권하는 말투면 다시 만들어도 같은 답이 나오기 쉽다
        val advice = ADVICE.containsMatchIn((listOf(reason) + keywords).joinToString(" "))
        if (!explained || evidenceIds.isEmpty() || cleaned.isEmpty() || advice) {
            return Verdict(published = true, explained = false, related = toSources(relatedIds), error = if (advice) "권유 표현" else null)
        }
        return Verdict(
            published = true, explained = true, keywords = keywords(stockName), reason = cleaned,
            evidence = toSources(evidenceIds), related = toSources(relatedIds),
        )
    }

    /** 종목 자신의 이름은 칩이 아니다 — 목록 줄에 이미 적혀 있다. */
    private fun keywords(stockName: String): List<String> =
        keywords.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_KEYWORD_LEN && it.lowercase() !in GENERIC_KEYWORDS && it != stockName }
            .distinct()
            .take(MAX_KEYWORDS)

    companion object {
        const val MAX_ARTICLES = 2
        const val MAX_KEYWORDS = 2

        /** 칩 하나 8자 안팎 — 이보다 길면 문장이지 키워드가 아니다 */
        const val MAX_KEYWORD_LEN = 12

        /** 어느 종목에나 붙는 말 — 칩으로 쓰면 아무것도 말하지 않는다 */
        private val GENERIC_KEYWORDS = setOf(
            "상승", "급등", "강세", "호재", "기대", "기대감", "주가", "반등", "특징주", "매수세", "신고가", "수급",
            "rally", "surge", "stock", "shares",
        )

        /** 직접적인 투자 권유. 목표가·투자의견 변경과 "매수세"·"외국인이 매수하며"·"팔아치운" 같은 사실 전달은 막지 않는다 */
        private val ADVICE = Regex("추천|매[수도]\\s*(?:하(?![며는고])|할|해(?!서))|담아(?![내낸])|사야|팔아(?!치)|\\bbuy\\b|\\bsell\\b", RegexOption.IGNORE_CASE)

        fun of(json: JsonNode): Draft = Draft(
            explained = json["explained"]?.asBoolean() ?: false,
            keywords = json["keywords"]?.values()?.map { it.asString() }.orEmpty(),
            reason = json["reason"]?.takeUnless { it.isNull }?.asString().orEmpty(),
            evidence = json["evidence"]?.values()?.map { it.asInt() }.orEmpty(),
            related = json["related"]?.values()?.map { it.asInt() }.orEmpty(),
        )
    }
}

/** [Draft.judge]의 판정. 게시하지 못했으면 [failure]가 그 까닭이다. */
data class Verdict(
    val published: Boolean,
    val explained: Boolean = false,
    val keywords: List<String> = emptyList(),
    val reason: String = "",
    val evidence: List<Source> = emptyList(),
    val related: List<Source> = emptyList(),
    val error: String? = null,
    val failure: Failure? = null,
)
