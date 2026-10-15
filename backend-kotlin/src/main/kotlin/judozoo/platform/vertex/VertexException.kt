package judozoo.platform.vertex

/**
 * 응답을 쓸 수 없다. [kind]가 부르는 쪽의 대응을 가른다. 메시지는 `ground`·`structure`로 시작한다 —
 * 두 호출 중 어디서 멈췄는지 바로 보이게.
 */
class VertexException(val kind: Kind, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** 이름은 Python 쪽 메트릭 라벨(`llm_failures_total{kind}`)과 같다. */
    enum class Kind(val label: String) {
        /** 시간 안에 답이 없다 */
        TIMEOUT("timeout"),

        /** 429·5xx·연결 실패 — 같은 호출 안에서 다시 물어도 안 풀렸다 */
        UNAVAILABLE("unavailable"),

        /** 400·403·404 등 — 우리 설정이 틀렸다. 사람이 고쳐야 한다 */
        CLIENT_ERROR("client_error"),

        /** 답은 왔는데 쓸 수 없다(깨진 JSON, 차단, 후보 없음) */
        BAD_ANSWER("bad_answer"),
    }
}
