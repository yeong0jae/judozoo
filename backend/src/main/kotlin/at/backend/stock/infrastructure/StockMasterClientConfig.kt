package at.backend.stock.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class StockMasterClientConfig {

    @Bean
    fun kisStockMasterClient(properties: StockMasterProperties): KisStockMasterClient =
        KisStockMasterClient(restClient = masterRestClient(), properties = properties)

    @Bean
    fun kisOverseasStockMasterClient(properties: StockMasterProperties): KisOverseasStockMasterClient =
        KisOverseasStockMasterClient(restClient = masterRestClient(), properties = properties)

    private fun masterRestClient(): RestClient =
        RestClient.builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            )
            .build()

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
