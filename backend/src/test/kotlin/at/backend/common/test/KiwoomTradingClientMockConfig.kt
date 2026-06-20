package at.backend.common.test

import at.backend.platform.kiwoom.client.KiwoomTradingClient
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 브로커(Kiwoom) 트레이딩 클라이언트를 막아 통합테스트가 실제 키움 API를 때리지 않게 한다.
 * relaxed 목 — 각 테스트가 필요한 메서드만 `every {}`로 덮어쓴다.
 */
@TestConfiguration
class KiwoomTradingClientMockConfig {

    @Bean
    @Primary
    fun mockKiwoomTradingClient(): KiwoomTradingClient = mockk(relaxed = true)
}
