package judozoo.library

import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Python(Starlette `SessionMiddleware`)이 발급한 세션 쿠키를 읽는다. **읽기만 한다** — 공존하는 동안(028)
 * 로그인·쿠키 발급은 Python이 맡는다.
 *
 * 쿠키 모양은 `base64(JSON).타임스탬프.서명`. itsdangerous `TimestampSigner` 기본값을 그대로 따른다:
 * 키 = SHA1("itsdangerous.Signer" + "signer" + secret), 서명 = HMAC-SHA1, 타임스탬프·서명은 패딩 없는 base64url.
 * 서명은 `base64(JSON).타임스탬프` 전체에 걸린다.
 */
class StarletteSession(
    secret: String,
    private val objectMapper: ObjectMapper,
    /** Starlette 기본값. Python이 따로 정하지 않았다. */
    private val maxAge: Duration = Duration.ofDays(14),
) {
    private val key = MessageDigest.getInstance("SHA-1")
        .digest("itsdangerous.Signer".toByteArray() + "signer".toByteArray() + secret.toByteArray())

    /** 서명이 맞고 만료되지 않았으면 세션 내용, 아니면 null. */
    fun read(cookie: String, now: Instant): Map<String, Any?>? {
        val signed = cookie.substringBeforeLast('.', "")
        val signature = cookie.substringAfterLast('.')
        if (signed.isEmpty() || !MessageDigest.isEqual(sign(signed), signature.toByteArray())) return null

        val payload = signed.substringBeforeLast('.', "")
        val timestamp = decodeUrl(signed.substringAfterLast('.')) ?: return null
        val age = Duration.between(Instant.ofEpochSecond(BigInteger(1, timestamp).toLong()), now)
        if (payload.isEmpty() || age.isNegative || age > maxAge) return null

        return runCatching {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(Base64.getDecoder().decode(payload), Map::class.java) as Map<String, Any?>
        }.getOrNull()
    }

    private fun sign(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        return Base64.getUrlEncoder().withoutPadding().encode(mac.doFinal(value.toByteArray()))
    }

    private fun decodeUrl(value: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
}
