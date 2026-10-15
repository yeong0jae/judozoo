package judozoo.library.web

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

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
