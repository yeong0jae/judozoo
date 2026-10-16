package judozoo

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["judozoo.scheduling.enabled=false"])
@Import(MySqlContainerConfig::class)
annotation class IntegrationTest

@TestConfiguration(proxyBeanMethods = false)
class MySqlContainerConfig {
    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
