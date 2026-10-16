package judozoo.insight.domain

data class Article(val source: String, val title: String, val url: String)

data class Articles(val items: List<Article> = emptyList()) {
    val size: Int get() = items.size

    companion object {
        fun of(sources: List<Source>) = Articles(sources.map { Article(it.domain, it.title, it.url) })
    }
}
