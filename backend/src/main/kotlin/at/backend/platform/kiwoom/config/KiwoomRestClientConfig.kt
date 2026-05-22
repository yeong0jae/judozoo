package at.backend.platform.kiwoom.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class KiwoomRestClientConfig {

    @Bean
    fun kiwoomRestClient(properties: KiwoomApiProperties): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .build()
}
