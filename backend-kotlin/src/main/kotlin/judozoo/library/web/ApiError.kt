package judozoo.library.web

import org.springframework.http.ResponseEntity

/** 오류 봉투 — 프론트(`apiFetch`)가 이 모양에서 `code`를 꺼낸다. Python과 같은 모양이다. */
data class ApiError(val code: String, val status: Int, val data: Any? = null) {
    fun toResponse(): ResponseEntity<ApiError> = ResponseEntity.status(status).body(this)
}
