package at.backend.library.web

data class ApiResponse<T>(
    val code: String,
    val status: Int,
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T) = ApiResponse("SUCCESS", 200, data)
        fun <T> created(data: T) = ApiResponse("SUCCESS", 201, data)
        fun <T> accepted(data: T) = ApiResponse("SUCCESS", 202, data)
        fun accepted() = ApiResponse<Nothing>("SUCCESS", 202)
        fun error(code: String, status: Int) = ApiResponse<Nothing>(code, status)
    }
}
