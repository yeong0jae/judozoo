package judozoo.library.web

import org.springframework.http.ResponseEntity

data class ApiResponse<T>(val code: String, val status: Int, val data: T? = null) {
    fun toResponse(): ResponseEntity<ApiResponse<T>> = ResponseEntity.status(status).body(this)

    companion object {
        fun <T> ok(data: T) = ApiResponse("SUCCESS", 200, data)

        fun <T> created(data: T) = ApiResponse("SUCCESS", 201, data)

        fun error(code: String, status: Int) = ApiResponse<Nothing>(code, status)
    }
}
