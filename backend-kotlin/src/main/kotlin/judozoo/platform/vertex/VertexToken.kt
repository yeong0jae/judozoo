package judozoo.platform.vertex

/** Vertex 호출 토큰. 운영 구현은 [AdcToken]. */
fun interface VertexToken {
    /** [refresh]면 들고 있던 토큰을 버리고 새로 받는다 — 401을 받았을 때. */
    fun get(refresh: Boolean): String
}
