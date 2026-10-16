package judozoo.library.web

import org.springframework.http.ResponseEntity

/**
 * API 응답 봉투 — **필드 이름·순서까지** 고정이다. 프론트(`apiFetch`)가 성공이면 `data`를, 실패면 `code`를 꺼낸다.
 * Python `library/web.py`의 `ApiResponse`와 같은 모양이다.
 */
data class ApiResponse<T>(val code: String, val status: Int, val data: T? = null) {
    /** 봉투의 [status]를 HTTP 상태로도 쓴다 — 오류일 때. */
    fun toResponse(): ResponseEntity<ApiResponse<T>> = ResponseEntity.status(status).body(this)

    companion object {
        fun <T> ok(data: T) = ApiResponse("SUCCESS", 200, data)

        /** **HTTP 상태는 200 그대로**다. 201은 봉투 안 [status]에만 나타난다 — 프론트가 그 모양을 기대한다. */
        fun <T> created(data: T) = ApiResponse("SUCCESS", 201, data)

        fun error(code: String, status: Int) = ApiResponse<Nothing>(code, status)
    }
}
