package judozoo.platform.kis

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken
import judozoo.library.brokertoken.BrokerTokenRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration
class KisConfig {
    @Bean
    fun kisClient(properties: KisProperties, repository: BrokerTokenRepository, transaction: TransactionTemplate, clock: Clock, registry: MeterRegistry) =
        KisClient(properties, IssuedToken(PROVIDER, repository, transaction, clock, KisTokenIssuer(properties, clock, registry)::issue), registry)

    companion object {
        const val PROVIDER = "KIS"
    }
}
