package judozoo.library.web

import jakarta.servlet.http.HttpServletRequest
import judozoo.library.BrokerTokenUnavailable
import judozoo.library.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BrokerTokenUnavailable::class)
    fun brokerTokenUnavailable(request: HttpServletRequest, e: BrokerTokenUnavailable): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("{} {} — {}", request.method, request.requestURI, e.message)
        return ApiResponse.error("INTERNAL_ERROR", 500).toResponse()
    }

    @ExceptionHandler(EntityNotFoundException::class, NoResourceFoundException::class)
    fun notFound(): ResponseEntity<ApiResponse<Nothing>> = ApiResponse.error("NOT_FOUND", 404).toResponse()

    /** 기존 API는 검증 실패에 400을 준다. 본문이 JSON이 아니거나 모양이 틀린 것도 같다. */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HandlerMethodValidationException::class,
    )
    fun invalidParameter(): ResponseEntity<ApiResponse<Nothing>> = ApiResponse.error("INVALID_PARAMETER", 400).toResponse()

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(): ResponseEntity<ApiResponse<Nothing>> = ApiResponse.error("METHOD_NOT_ALLOWED", 405).toResponse()

    /** 엔드포인트가 직접 던지는 상태 — `reason`이 그대로 code가 된다(`BET_REJECTED` 등). */
    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException): ResponseEntity<ApiResponse<Nothing>> =
        ApiResponse.error(e.reason ?: "ERROR", e.statusCode.value()).toResponse()

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception", e)
        return ApiResponse.error("INTERNAL_ERROR", 500).toResponse()
    }
}
