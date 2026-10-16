package judozoo.platform.kiwoom

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.IssuedToken
import judozoo.library.brokertoken.BrokerTokenRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
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
    ) = KiwoomClient(properties, IssuedToken(PROVIDER, repository, transaction, clock, KiwoomTokenIssuer(properties, clock, registry)::issue), clock, registry)

    companion object {
        const val PROVIDER = "KIWOOM"
    }
}
