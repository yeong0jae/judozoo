package at.backend.common.test

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

@SpringBootTest
@ActiveProfiles("test")
@Import(KiwoomTradingClientMockConfig::class)
abstract class IntegrationTestBase : FunSpec() {

    override fun extensions() = listOf(SpringExtension)

    companion object {
        // withReuse: 실행 간 컨테이너를 살려둬 매번 MySQL 기동·스키마 생성을 반복하지 않는다.
        // 로컬에서만 동작하며 ~/.testcontainers.properties 에 testcontainers.reuse.enable=true 가 필요하다.
        private val mysql = MySQLContainer("mysql:8.4").withReuse(true).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }
}
