package judozoo.insight.domain

import java.time.LocalDateTime

interface Publication {
    val generatedAt: LocalDateTime
    val published: Boolean
    val explained: Boolean
}

object Shown {
    fun <T : Publication> of(rows: List<T>): T? {
        val published = rows.filter { it.published }.sortedBy { it.generatedAt }
        return published.lastOrNull { it.explained } ?: published.lastOrNull()
    }
}
