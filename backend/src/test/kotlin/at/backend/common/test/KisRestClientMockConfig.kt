package at.backend.common.test

import at.backend.platform.kis.client.KisRestClient
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class KisRestClientMockConfig {

    @Bean
    @Primary
    fun mockKisRestClient(): KisRestClient = mockk()
}
