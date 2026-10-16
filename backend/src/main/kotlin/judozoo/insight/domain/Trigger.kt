package judozoo.insight.domain

enum class Trigger(val value: String) {
    ENTRY("entry"),
    SCHEDULED("scheduled"),
    RETRY("retry"),
}
