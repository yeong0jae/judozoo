package judozoo.insight.domain

import org.springframework.web.util.HtmlUtils

data class Source(val id: Int, val domain: String, val title: String, val url: String)

class Sources(val items: List<Source> = emptyList()) : Iterable<Source> by items {
    val size: Int get() = items.size

    operator fun get(id: Int): Source? = items.firstOrNull { it.id == id }

    fun listing(): String = items.joinToString("\n") { "[${it.id}] ${it.domain} | ${it.title}" }.ifEmpty { "(기사 없음)" }

    data class Raw(val domain: String, val title: String, val url: String)

    companion object {
        const val MIN_TITLE_LEN = 10

        private val BREADCRUMB = Regex("\\s+<\\s+.*$")

        private val OUTLET = Regex("\\s+[-|]\\s+[^-|]{1,20}$")

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

        private fun outlet(label: String): String = HtmlUtils.htmlUnescape(label).split(" | ").last().trim()
    }
}
