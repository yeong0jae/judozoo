package judozoo.auth.domain

/**
 * 세션 쿠키에 담기는 최소 정보.
 *
 * **구글 `sub`은 담지 않는다.** 쿠키는 서명만 되고 암호화되지 않아 누구나 읽을 수 있다.
 * 사람을 가리키는 값은 이 시스템이 발급한 `id`면 충분하다.
 */
data class CurrentUser(val id: Long, val email: String) {
    /** 세션에 담는 모양. 구글 `sub`은 넣지 않는다. */
    fun toSession(): Map<String, Any> = mapOf("id" to id, "email" to email)

    companion object {
        /** 세션에서 이 키 아래에 담긴다. Python `SESSION_KEY`와 같다. */
        const val SESSION_KEY = "user"

        fun fromSession(raw: Any?): CurrentUser? {
            if (raw !is Map<*, *>) return null
            val id = raw["id"]
            val email = raw["email"]
            // JSON 정수만 받는다. true·1.5·"7"은 사용자로 치지 않는다
            if ((id !is Int && id !is Long) || email !is String) return null
            return CurrentUser((id as Number).toLong(), email)
        }
    }
}
