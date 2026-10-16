package judozoo.library

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@ConditionalOnProperty("judozoo.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig
