package judozoo.insight.domain

import org.springframework.web.util.HtmlUtils

/** Gemini에 번호로 보여줄 기사 한 건. */
data class Source(val id: Int, val domain: String, val title: String, val url: String)

/** Gemini에 번호로 보여줄 기사 목록. 모델은 URL을 쓰지 않고 이 번호만 고른다 — 주소를 지어낼 틈이 없다. */
class Sources(val items: List<Source> = emptyList()) : Iterable<Source> by items {
    val size: Int get() = items.size

    operator fun get(id: Int): Source? = items.firstOrNull { it.id == id }

    fun listing(): String = items.joinToString("\n") { "[${it.id}] ${it.domain} | ${it.title}" }.ifEmpty { "(기사 없음)" }

    /** 한 기사를 넣는 날것 — (매체, 제목, 실제 URL). */
    data class Raw(val domain: String, val title: String, val url: String)

    companion object {
        /** 이보다 짧은 제목은 기사가 아니라 사이트 이름이다("알파스퀘어") */
        const val MIN_TITLE_LEN = 10

        /** 페이지 제목 뒤에 붙는 사이트 경로("< 증시 < 기사본문") */
        private val BREADCRUMB = Regex("\\s+<\\s+.*$")

        /** 뒤에 붙는 매체명(" - 파이낸셜뉴스", " | 한국경제"). 20자 넘는 꼬리는 제목의 일부로 본다 */
        private val OUTLET = Regex("\\s+[-|]\\s+[^-|]{1,20}$")

        /** 시세 중계 기사 — 오른 이유를 말하지 않고 가격만 전한다 */
        private val PRICE_NOISE = Regex(
            listOf(
                "주가,\\s*\\d{1,2}월\\s*\\d{1,2}일",
                "장중\\s*[\\d,]+원\\s*[\\d.]+%\\s*(상승|하락)",
                "VI\\s*발동",
                "주가\\s*(상승|하락)\\s*중$",
                "\\b52-Week (High|Low)\\b",
                "\\bHits? (a )?(New )?Record High\\b",
                "\\b(Opened|Moved) (Up|Down) by\\b",
                "\\bStock Price (Up|Down) [\\d.]+%",
                "\\bTime to Buy\\b",
                "\\bPrediction:",
                "\\bgains? [\\d.]+ percent\\b",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )

        /** (매체, 제목, 실제 URL)들을 정리하고 시세 기사를 뺀 뒤 1부터 번호를 붙인다. */
        fun of(raw: List<Raw>): Sources {
            val kept = mutableListOf<Source>()
            for ((domain, rawTitle, url) in raw) {
                val title = cleanTitle(rawTitle)
                if (title.length < MIN_TITLE_LEN || PRICE_NOISE.containsMatchIn(title) || kept.any { it.url == url }) continue
                kept += Source(kept.size + 1, outlet(domain), title, url)
            }
            return Sources(kept)
        }

        fun cleanTitle(raw: String): String {
            val title = HtmlUtils.htmlUnescape(raw.replace(Regex("\\s+"), " ")).trim()
            return title.replace(BREADCRUMB, "").replace(OUTLET, "").trim()
        }

        /** 포털을 거친 기사는 매체명이 "Daum | 서울경제"로 온다 — 원래 매체만 남긴다. */
        private fun outlet(label: String): String = HtmlUtils.htmlUnescape(label).split(" | ").last().trim()
    }
}
