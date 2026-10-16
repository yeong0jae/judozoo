package judozoo.insight.domain

class Publications<T : Publication>(private val rows: List<T>) {
    fun shown(): T? {
        val published = rows.filter { it.published }.sortedBy { it.generatedAt }
        return published.lastOrNull { it.explained } ?: published.lastOrNull()
    }
}
