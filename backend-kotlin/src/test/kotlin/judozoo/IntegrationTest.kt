package judozoo

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Docker가 필요한 테스트. Testcontainers MySQL에 붙은 앱 전체를 띄운다(Python `통합_db`).
 * `./gradlew unitTest`는 이 태그를 건너뛴다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlContainerConfig::class)
annotation class IntegrationTest

@TestConfiguration(proxyBeanMethods = false)
class MySqlContainerConfig {
    /** 운영(Cloud SQL)과 Python 테스트와 같은 판. */
    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
