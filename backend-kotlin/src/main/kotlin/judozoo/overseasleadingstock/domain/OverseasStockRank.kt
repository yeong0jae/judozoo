package judozoo.overseasleadingstock.domain

/** 해외 거래대금 상위 풀의 한 종목. 외부 의존 없음. */
data class OverseasStockRank(
    val rank: Int,
    val exchange: String,
    val symbol: String,
    val name: String,
    val ename: String,
    val price: Double,
    /** 전일 대비. 등락 방향 부호가 붙어 있다 */
    val diff: Double,
    val rate: Double,
    val tradingValue: Double,
) {
    /** 거래대금순위 API엔 ETF 구분 필드가 없어 영문명 키워드로 판별한다(휴리스틱). */
    val isEtf: Boolean get() = ename.uppercase().let { upper -> ETF_KEYWORDS.any { it in upper } }

    /** 전일 종가 — 대비(diff)는 현재가에서 전일 종가를 뺀 값이다. */
    val previousClose: Double get() = price - diff

    fun ranked(rank: Int) = copy(rank = rank)

    companion object {
        /** 발행사 브랜드 위주로 잡아 일반기업 오탐을 줄인다 — TRUST·FUND 등 흔한 단어는 일부러 제외. */
        private val ETF_KEYWORDS = listOf(
            " ETF", " ETN", "ISHARES", "SPDR", "INVESCO", "PROSHARES", "DIREXION",
            "VANGUARD", "GLOBAL X", "VANECK", "GRANITESHARES", "WISDOMTREE", "FIRST TRUST",
        )
    }
}
