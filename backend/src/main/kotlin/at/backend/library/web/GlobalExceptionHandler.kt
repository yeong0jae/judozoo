package at.backend.library.web

import at.backend.library.exception.EntityNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = KotlinLogging.logger {}

    @ExceptionHandler(EntityNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleEntityNotFound(ex: EntityNotFoundException) =
        ApiResponse.error("NOT_FOUND", 404)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException) =
        ApiResponse.error("INVALID_PARAMETER", 400)

    /** 쿼리 파라미터 타입 변환 실패(정의에 없는 enum, 숫자 아닌 값 등) — 잘못된 요청이지 서버 오류가 아니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException) =
        ApiResponse.error("INVALID_PARAMETER", 400)

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleUnexpected(ex: Exception): ApiResponse<Nothing> {
        log.error(ex) { "Unhandled exception" }
        return ApiResponse.error("INTERNAL_ERROR", 500)
    }
}
