package judozoo.library

/** 조회 대상이 없다. 전역 핸들러가 404 + NOT_FOUND로 바꾼다. */
class EntityNotFoundException(message: String) : RuntimeException(message)
