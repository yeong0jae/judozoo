package judozoo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class JudozooApplication

/** `judozoo.*` 설정. */
@ConfigurationProperties("judozoo")
data class JudozooProperties(
    /** 세션 쿠키 서명 키. Python `SESSION_SECRET`과 같은 값이다. */
    val sessionSecret: String,
)

fun main(args: Array<String>) {
    runApplication<JudozooApplication>(*args)
}
