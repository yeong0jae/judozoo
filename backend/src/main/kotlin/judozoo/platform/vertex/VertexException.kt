package judozoo.platform.vertex

class VertexException(val kind: Kind, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Kind(val label: String) {
        TIMEOUT("timeout"),

        UNAVAILABLE("unavailable"),

        CLIENT_ERROR("client_error"),

        BAD_ANSWER("bad_answer"),
    }
}
