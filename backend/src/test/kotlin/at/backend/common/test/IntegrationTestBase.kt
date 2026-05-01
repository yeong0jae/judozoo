package at.backend.common.test

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

@SpringBootTest
abstract class IntegrationTestBase {

    companion object {
        private val mysql = MySQLContainer("mysql:8.4")
            .withDatabaseName("trading")
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "${mysql.jdbcUrl}?useSSL=false&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true"
            }
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }
}
