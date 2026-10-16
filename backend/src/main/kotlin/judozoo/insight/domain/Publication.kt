package judozoo.insight.domain

import java.time.LocalDateTime

interface Publication {
    val generatedAt: LocalDateTime
    val published: Boolean
    val explained: Boolean
}
