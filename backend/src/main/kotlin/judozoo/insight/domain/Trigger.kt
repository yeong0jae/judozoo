package judozoo.insight.domain

/** 왜 만들었나 — DB에는 소문자 글자([value])로 남는다. */
enum class Trigger(val value: String) {
    ENTRY("entry"),
    SCHEDULED("scheduled"),
    RETRY("retry"),
}
