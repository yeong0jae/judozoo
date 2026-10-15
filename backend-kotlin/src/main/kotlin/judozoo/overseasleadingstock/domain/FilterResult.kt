package judozoo.overseasleadingstock.domain

/** 종목 상세의 조건 하나. [value]·[threshold]는 화면이 눈금 막대를 그리는 숫자 — 막대가 없는 조건은 null. */
data class FilterResult(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
    val value: Double? = null,
    val threshold: Double? = null,
)
