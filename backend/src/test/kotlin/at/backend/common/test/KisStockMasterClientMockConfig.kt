package at.backend.common.test

import at.backend.stock.infrastructure.KisStockMasterClient
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 통합 테스트에서 부팅 시 종목 카탈로그 갱신이 실제 CDN을 때리지 않도록 마스터 클라이언트를 막는다.
 * relaxed 목이라 fetchAll()은 기본 빈 리스트를 반환한다.
 */
@TestConfiguration
class KisStockMasterClientMockConfig {

    @Bean
    @Primary
    fun mockKisStockMasterClient(): KisStockMasterClient = mockk(relaxed = true)
}
