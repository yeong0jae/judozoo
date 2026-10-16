package judozoo.library

import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ClientObservationMeters {
    @Bean
    fun dropClientObservationMeters(): MeterFilter = MeterFilter.denyNameStartsWith("http.client.requests")
}
