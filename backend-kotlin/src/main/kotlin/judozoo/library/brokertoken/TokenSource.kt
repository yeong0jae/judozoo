package judozoo.library.brokertoken

/** 브로커 클라이언트가 쓰는 액세스 토큰. 공존하는 동안은 [BorrowedToken]이 유일한 구현이다. */
interface TokenSource {
    fun get(): String

    /** 브로커가 이 토큰을 거부했다. */
    fun invalidate()
}
