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
// 폴러가 실제 브로커·야후를 부르지 않게 스케줄링을 끈다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["judozoo.scheduling.enabled=false"])
@Import(MySqlContainerConfig::class)
annotation class IntegrationTest

@TestConfiguration(proxyBeanMethods = false)
class MySqlContainerConfig {
    /** 운영(Cloud SQL 8.0)과 같은 계열. 테이블은 기동할 때 Flyway가 만든다 — JPA는 `validate`만 한다. */
    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
