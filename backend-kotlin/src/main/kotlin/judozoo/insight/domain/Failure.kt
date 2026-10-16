package judozoo.insight.domain

/** 게시하지 못한 까닭. 다시 할지, 서킷이 셀지가 여기서 갈린다(026 §실패 처리). DB·메트릭 라벨에는 [value]로 남는다. */
enum class Failure(val value: String) {
    /** 시간 안에 답이 없다 */
    TIMEOUT("timeout"),

    /** 로컬 연결 풀 포화 — 재시도하지만 Vertex 서킷에는 영향을 주지 않는다. Kotlin 클라이언트는 내지 않지만 옛 행에 남아 있다 */
    POOL_TIMEOUT("pool_timeout"),

    /** 429·5xx — 같은 호출 안에서 다시 물어도 안 풀렸다 */
    UNAVAILABLE("unavailable"),

    /** 400·403·404 등 — 우리 설정이 틀렸다. 다시 해도 같다 */
    CLIENT_ERROR("client_error"),

    /** 답은 왔는데 쓸 수 없다 — 깨진 JSON, 목록에 없는 기사 번호 */
    BAD_ANSWER("bad_answer"),
    ;

    /** 시간이 지나면 풀릴 수 있는 실패 — 종목을 다시 만든다. */
    val transient: Boolean get() = this == TIMEOUT || this == POOL_TIMEOUT || this == UNAVAILABLE

    companion object {
        fun of(value: String): Failure = entries.first { it.value == value }
    }
}
