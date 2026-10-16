package judozoo.auth.domain

data class CurrentUser(val id: Long, val email: String) {
    fun toSession(): Map<String, Any> = mapOf("id" to id, "email" to email)

    companion object {
        const val SESSION_KEY = "user"

        fun fromSession(raw: Any?): CurrentUser? {
            if (raw !is Map<*, *>) return null
            val id = raw["id"]
            val email = raw["email"]
            if ((id !is Int && id !is Long) || email !is String) return null
            return CurrentUser((id as Number).toLong(), email)
        }
    }
}
