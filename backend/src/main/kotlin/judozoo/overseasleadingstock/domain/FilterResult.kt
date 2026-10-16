package judozoo.overseasleadingstock.domain

data class FilterResult(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
    val value: Double? = null,
    val threshold: Double? = null,
)
