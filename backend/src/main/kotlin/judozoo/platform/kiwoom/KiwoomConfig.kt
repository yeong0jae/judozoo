package judozoo.platform.kiwoom

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.BrokerTokenRepository
import judozoo.library.brokertoken.IssuedToken
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
class KiwoomConfig {
    @Bean
    fun kiwoomClient(
        properties: KiwoomProperties,
        repository: BrokerTokenRepository,
        clock: Clock,
        registry: MeterRegistry,
        transaction: TransactionTemplate,
        restClient: RestClient.Builder,
    ) = KiwoomClient(
        properties, IssuedToken(PROVIDER, repository, transaction, clock, KiwoomTokenIssuer(properties, clock, registry, restClient)::issue), clock, registry,
        restClient,
    )

    companion object {
        const val PROVIDER = "KIWOOM"
    }
}
