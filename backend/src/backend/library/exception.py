"""도메인 예외."""


class EntityNotFoundError(Exception):
    """조회 대상이 없다. 전역 핸들러가 404 + NOT_FOUND로 바꾼다."""


class BrokerTokenUnavailable(RuntimeError):
    """브로커가 토큰 발급을 거부해 백오프 중이다.

    **예상된 상태**다 — 버그가 아니다. 전역 핸들러가 스택트레이스 없이 한 줄로 남기고
    기존 오류 봉투(500 INTERNAL_ERROR)를 돌려준다."""
