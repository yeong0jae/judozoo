package judozoo.insight.domain

enum class Failure(val value: String) {
    TIMEOUT("timeout"),

    POOL_TIMEOUT("pool_timeout"),

    UNAVAILABLE("unavailable"),

    CLIENT_ERROR("client_error"),

    BAD_ANSWER("bad_answer"),
    ;

    val transient: Boolean get() = this == TIMEOUT || this == POOL_TIMEOUT || this == UNAVAILABLE

    companion object {
        fun of(value: String): Failure = entries.first { it.value == value }
    }
}
