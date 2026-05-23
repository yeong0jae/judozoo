package at.backend.platform.kiwoom.client

/**
 * Kiwoom이 HTTP 200으로 명시적 거부(return_code != 0)를 반환한 케이스.
 * code는 거부 코드(return_code 문자열화), msg는 return_msg 본문.
 */
class KiwoomOrderRejectedException(
    val code: String,
    msg: String,
) : RuntimeException("[$code] $msg")
