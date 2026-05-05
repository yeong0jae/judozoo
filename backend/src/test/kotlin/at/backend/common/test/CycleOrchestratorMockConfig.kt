package at.backend.common.test

import at.backend.trading.application.CycleOrchestrator
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class CycleOrchestratorMockConfig {

    @Bean
    @Primary
    fun mockCycleOrchestrator(): CycleOrchestrator = mockk(relaxed = true)
}
