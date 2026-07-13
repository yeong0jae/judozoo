package at.backend.news.domain

import java.time.LocalDateTime

/**
 * 종목 관련 뉴스·공시 한 건 — KIS 종합 시황/공시. 제목만 제공되고 원문 링크는 없다.
 * 공시는 언론 기사와 같은 목록에 섞여 오므로, 제공 업체 코드로 스스로 구분한다.
 */
data class StockNews(
    val seqNo: String,
    val title: String,
    val source: String, // 표시용 출처명 — 언론사명 또는 "공시"
    private val providerCode: String,
    val publishedAt: LocalDateTime,
) {
    /** 언론 기사가 아니라 거래소·코스닥 등의 공시인지. */
    val disclosure: Boolean get() = providerCode in DISCLOSURE_CODES

    companion object {
        // 장내·코스닥·프리보드·기타·코넥스 공시
        private val DISCLOSURE_CODES = setOf("F", "G", "H", "I", "N")
    }
}
