package at.backend.leadingstock.application.filter

data class FilterEvaluationResult(
    val filterName: String,
    val criteriaDescription: String,
    val actualValue: String,
    val passed: Boolean,
)
