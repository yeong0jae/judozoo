package judozoo.insight.domain

import tools.jackson.databind.JsonNode

data class Draft(val explained: Boolean, val keywords: List<String>, val reason: String, val evidence: List<Int>, val related: List<Int>) {
    fun judge(sources: Sources, stockName: String = ""): Verdict {
        val unknown = (evidence + related).filter { sources[it] == null }
        if (unknown.isNotEmpty()) return Verdict(published = false, error = "목록에 없는 기사 번호 $unknown", failure = Failure.BAD_ANSWER)

        val evidenceIds = evidence.distinct().take(MAX_ARTICLES)
        val relatedIds = related.distinct().filterNot { it in evidenceIds }.take(MAX_ARTICLES)
        fun toSources(ids: List<Int>) = ids.mapNotNull { sources[it] }

        val cleaned = reason.trim().trimEnd('.', '。').trim()
        val advice = ADVICE.containsMatchIn((listOf(reason) + keywords).joinToString(" "))
        if (!explained || evidenceIds.isEmpty() || cleaned.isEmpty() || advice) {
            return Verdict(published = true, explained = false, related = toSources(relatedIds), error = if (advice) "권유 표현" else null)
        }
        return Verdict(
            published = true, explained = true, keywords = keywords(stockName), reason = cleaned,
            evidence = toSources(evidenceIds), related = toSources(relatedIds),
        )
    }

    private fun keywords(stockName: String): List<String> =
        keywords.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_KEYWORD_LEN && it.lowercase() !in GENERIC_KEYWORDS && it != stockName }
            .distinct()
            .take(MAX_KEYWORDS)

    companion object {
        const val MAX_ARTICLES = 2
        const val MAX_KEYWORDS = 2

        const val MAX_KEYWORD_LEN = 12

        private val GENERIC_KEYWORDS = setOf(
            "상승", "급등", "강세", "호재", "기대", "기대감", "주가", "반등", "특징주", "매수세", "신고가", "수급",
            "rally", "surge", "stock", "shares",
        )

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
