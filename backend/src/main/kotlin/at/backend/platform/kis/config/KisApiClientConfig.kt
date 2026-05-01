package at.backend.platform.kis.config

import at.backend.platform.kis.client.KisAuthClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class KisApiClientConfig {

    @Bean
    fun kisAuthClient(properties: KisProperties): KisAuthClient =
        KisAuthClient(
            appKey = properties.appKey,
            appSecret = properties.appSecret,
            restClient = RestClient.builder()
                .baseUrl(properties.baseUrl)
                .requestFactory(SimpleClientHttpRequestFactory())
                .build(),
        )
}
