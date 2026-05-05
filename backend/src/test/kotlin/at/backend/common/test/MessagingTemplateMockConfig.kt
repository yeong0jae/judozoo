package at.backend.common.test

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * STOMP 발행을 외부 transport 어댑터로 취급해 application 통합 테스트에서 mock으로 교체.
 * 사슬 끝(`messagingTemplate.convertAndSend`)이 호출됐는지를 검증한다.
 */
@TestConfiguration
class MessagingTemplateMockConfig {

    @Bean
    @Primary
    fun mockSimpMessagingTemplate(): SimpMessagingTemplate = mockk(relaxed = true)
}
