package judozoo.library.brokertoken

/** 브로커 클라이언트가 쓰는 액세스 토큰. 구현은 [IssuedToken]이다. */
interface TokenSource {
    fun get(): String

    /** 브로커가 이 토큰을 거부했다. 다음 [get]이 새로 발급한다. */
    fun invalidate()
}
