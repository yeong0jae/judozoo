package judozoo.platform.vertex

fun interface VertexToken {
    fun get(refresh: Boolean): String
}
