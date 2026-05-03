package at.backend.common.test

import at.backend.library.time.TimeProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.LocalDateTime

@TestConfiguration
class FixedTimeProviderConfig {

    @Bean
    @Primary
    fun fixedTimeProvider(): MutableTimeProvider = MutableTimeProvider(DEFAULT_NOW)

    companion object {
        val DEFAULT_NOW: LocalDateTime = LocalDateTime.of(2026, 1, 2, 10, 0)
    }
}

class MutableTimeProvider(initial: LocalDateTime) : TimeProvider {
    var current: LocalDateTime = initial
    override fun now(): LocalDateTime = current
}
