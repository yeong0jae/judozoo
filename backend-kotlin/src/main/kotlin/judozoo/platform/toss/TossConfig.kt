package judozoo.platform.toss

import io.micrometer.core.instrument.MeterRegistry
import judozoo.library.brokertoken.BorrowedToken
import judozoo.library.brokertoken.BrokerTokenRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TossConfig {
    @Bean
    fun tossClient(properties: TossProperties, repository: BrokerTokenRepository, clock: Clock, registry: MeterRegistry) =
        TossClient(properties, BorrowedToken(PROVIDER, repository, clock), registry)

    companion object {
        /** `broker_token.provider`. Python과 같은 값이다. */
        const val PROVIDER = "TOSS"
    }
}
