package judozoo.insight.domain

import java.time.LocalDateTime

/** 화면에 내줄 수 있는 생성 기록. */
interface Publication {
    val generatedAt: LocalDateTime
    val published: Boolean
    val explained: Boolean
}

/** 한 종목의 여러 생성 기록 중 화면에 보일 하나. */
object Shown {
    /**
     * 가장 최근의 게시·설명된 행. 없을 때만 가장 최근의 게시된 설명 없음 —
     * 검색 결과는 매번 달라서, 나중에 나온 설명 없음이 멀쩡하던 사유를 지우면 안 된다.
     */
    fun <T : Publication> of(rows: List<T>): T? {
        val published = rows.filter { it.published }.sortedBy { it.generatedAt }
        return published.lastOrNull { it.explained } ?: published.lastOrNull()
    }
}
