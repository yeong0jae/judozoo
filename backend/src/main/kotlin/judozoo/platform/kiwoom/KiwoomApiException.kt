package judozoo.platform.kiwoom

class KiwoomApiException(val apiId: String, val code: Int, message: String?) :
    RuntimeException("Kiwoom $apiId error. code=$code msg=$message") {
    val isInvalidToken: Boolean = code == TOKEN_INVALID_CODE ||
        message.orEmpty().let { "8005" in it || "Token이 유효하지 않" in it }

    companion object {
        const val TOKEN_INVALID_CODE = 8005
    }
}
