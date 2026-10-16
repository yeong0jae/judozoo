package judozoo.library

import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StarletteSession(
    secret: String,
    private val objectMapper: ObjectMapper,
    private val maxAge: Duration = Duration.ofDays(14),
) {
    private val key = MessageDigest.getInstance("SHA-1")
        .digest("itsdangerous.Signer".toByteArray() + "signer".toByteArray() + secret.toByteArray())

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

    fun write(data: Map<String, Any?>, now: Instant): String {
        val payload = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(data))
        val timestamp = Base64.getUrlEncoder().withoutPadding().encodeToString(BigInteger.valueOf(now.epochSecond).toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        val signed = "$payload.$timestamp"
        return "$signed.${String(sign(signed))}"
    }

    private fun sign(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        return Base64.getUrlEncoder().withoutPadding().encode(mac.doFinal(value.toByteArray()))
    }

    private fun decodeUrl(value: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
}
