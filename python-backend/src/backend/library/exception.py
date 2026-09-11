"""도메인 예외. Kotlin `library.exception`에 대응."""


class EntityNotFoundError(Exception):
    """조회 대상이 없다. 전역 핸들러가 404 + NOT_FOUND로 바꾼다."""
