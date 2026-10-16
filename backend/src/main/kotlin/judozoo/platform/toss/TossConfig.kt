package judozoo.platform.toss

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken
import judozoo.library.brokertoken.BrokerTokenRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration
class TossConfig {
    @Bean
    fun tossClient(properties: TossProperties, repository: BrokerTokenRepository, transaction: TransactionTemplate, clock: Clock, registry: MeterRegistry) =
        TossClient(properties, IssuedToken(PROVIDER, repository, transaction, clock, TossTokenIssuer(properties, clock, registry)::issue), registry)

    companion object {
        const val PROVIDER = "TOSS"
    }
}
