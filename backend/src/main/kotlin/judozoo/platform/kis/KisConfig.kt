package judozoo.platform.kis

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.BrokerTokenRepository
import judozoo.library.brokertoken.IssuedToken
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
class KisConfig {
    @Bean
    fun kisClient(properties: KisProperties, repository: BrokerTokenRepository, transaction: TransactionTemplate, clock: Clock, registry: MeterRegistry, restClient: RestClient.Builder) =
        KisClient(
            properties, IssuedToken(PROVIDER, repository, transaction, clock, KisTokenIssuer(properties, clock, registry, restClient)::issue), registry,
            restClient = restClient,
        )

    companion object {
        const val PROVIDER = "KIS"
    }
}
