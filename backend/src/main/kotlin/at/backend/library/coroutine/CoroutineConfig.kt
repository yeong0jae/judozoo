package at.backend.library.coroutine

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CoroutineConfig {

    private val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("trading"))

    @Bean
    fun applicationCoroutineScope(): CoroutineScope = applicationScope

    @PreDestroy
    fun shutdown() {
        applicationScope.cancel()
    }
}
