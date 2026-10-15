package judozoo.library

/**
 * 브로커가 토큰 발급을 거부해 백오프 중이다.
 *
 * **예상된 상태**다 — 버그가 아니다. 전역 핸들러가 스택트레이스 없이 한 줄로 남기고 500 INTERNAL_ERROR로 답한다.
 */
class BrokerTokenUnavailable(message: String) : RuntimeException(message)
