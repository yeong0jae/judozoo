package judozoo.library.web

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val jdbc: JdbcTemplate) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "UP")

    @GetMapping("/health/db")
    fun healthDb(): Map<String, String> {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        return mapOf("status" to "UP", "database" to "UP")
    }
}
