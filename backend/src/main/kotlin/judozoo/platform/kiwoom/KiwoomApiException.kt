package judozoo.platform.kiwoom

/** 키움이 `return_code` ≠ 0으로 답했다. */
class KiwoomApiException(val apiId: String, val code: Int, message: String?) :
    RuntimeException("Kiwoom $apiId error. code=$code msg=$message") {

    /**
     * 토큰 무효(8005). 같은 앱키로 다른 프로세스가 새 토큰을 받으면 이쪽 토큰이 즉시 죽는다.
     * 코드 대신 메시지에만 실려 오는 경우가 있어 둘 다 본다.
     */
    val isInvalidToken: Boolean = code == TOKEN_INVALID_CODE ||
        message.orEmpty().let { "8005" in it || "Token이 유효하지 않" in it }

    companion object {
        const val TOKEN_INVALID_CODE = 8005
    }
}
