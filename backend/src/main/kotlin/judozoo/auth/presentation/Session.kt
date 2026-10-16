package judozoo.auth.presentation

import judozoo.auth.domain.CurrentUser

class Session(val values: Map<String, Any?> = emptyMap()) {
    data class LoginAttempt(val nonce: String, val expiresAt: Long) {
        fun expiredAt(epochSecond: Long): Boolean = epochSecond > expiresAt
    }

    val user: CurrentUser? get() = userOf(values[USER])

    val isEmpty: Boolean get() = values.isEmpty()

    val carriesStaleUser: Boolean get() = user?.let { (values[USER] as Map<*, *>).keys != userValues(it).keys } ?: false

    fun withUser(user: CurrentUser) = Session(values + (USER to userValues(user)))

    fun withoutUser() = Session(values - USER)

    fun startLogin(state: String, nonce: String, expiresAt: Long) =
        Session(values + (STATE_PREFIX + state to mapOf("data" to mapOf("nonce" to nonce), "exp" to expiresAt)))

    fun loginAttempt(state: String?): LoginAttempt? {
        val saved = state?.let { values[STATE_PREFIX + it] } as? Map<*, *> ?: return null
        val nonce = (saved["data"] as? Map<*, *>)?.get("nonce") as? String ?: return null
        return LoginAttempt(nonce, (saved["exp"] as? Number)?.toLong() ?: 0)
    }

    fun withoutLogin(state: String?) = Session(values - (STATE_PREFIX + state))

    companion object {
        private const val USER = "user"

        private const val STATE_PREFIX = "_state_google_"

        private fun userValues(user: CurrentUser): Map<String, Any> = mapOf("id" to user.id, "email" to user.email)

        private fun userOf(raw: Any?): CurrentUser? {
            if (raw !is Map<*, *>) return null
            val id = raw["id"]
            val email = raw["email"]
            if ((id !is Int && id !is Long) || email !is String) return null
            return CurrentUser((id as Number).toLong(), email)
        }
    }
}
