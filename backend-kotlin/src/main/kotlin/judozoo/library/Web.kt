package judozoo.library

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.server.ResponseStatusException

/** 오류 봉투 — 프론트(`apiFetch`)가 이 모양에서 `code`를 꺼낸다. Python과 같은 모양이다. */
data class ApiError(val code: String, val status: Int, val data: Any? = null) {
    fun toResponse(): ResponseEntity<ApiError> = ResponseEntity.status(status).body(this)
}

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BrokerTokenUnavailable::class)
    fun brokerTokenUnavailable(request: HttpServletRequest, e: BrokerTokenUnavailable): ResponseEntity<ApiError> {
        log.warn("{} {} — {}", request.method, request.requestURI, e.message)
        return ApiError("INTERNAL_ERROR", 500).toResponse()
    }

    @ExceptionHandler(EntityNotFoundException::class, NoResourceFoundException::class)
    fun notFound(): ResponseEntity<ApiError> = ApiError("NOT_FOUND", 404).toResponse()

    /** 기존 API는 검증 실패에 400을 준다. */
    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HandlerMethodValidationException::class,
    )
    fun invalidParameter(): ResponseEntity<ApiError> = ApiError("INVALID_PARAMETER", 400).toResponse()

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(): ResponseEntity<ApiError> = ApiError("METHOD_NOT_ALLOWED", 405).toResponse()

    /** 엔드포인트가 직접 던지는 상태 — `reason`이 그대로 code가 된다(`BET_REJECTED` 등). */
    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException): ResponseEntity<ApiError> =
        ApiError(e.reason ?: "ERROR", e.statusCode.value()).toResponse()

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception", e)
        return ApiError("INTERNAL_ERROR", 500).toResponse()
    }
}

@RestController
class HealthController(private val jdbc: JdbcTemplate) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "UP")

    /** DB 연결까지 확인한다. 기동 순서 문제를 헬스체크로 드러내기 위한 것. */
    @GetMapping("/health/db")
    fun healthDb(): Map<String, String> {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        return mapOf("status" to "UP", "database" to "UP")
    }
}
