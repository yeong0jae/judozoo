package judozoo.leadingstock.domain

/** 종목 상세의 조건 하나. 문자열은 화면에 그대로 나간다. [value]·[threshold]는 눈금 막대용 숫자 — 막대가 없는 조건이나 값이 없으면 null. */
data class FilterResult(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
    val value: Double? = null,
    val threshold: Double? = null,
)
