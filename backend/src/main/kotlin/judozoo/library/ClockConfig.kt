package judozoo.library

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

val KST: ZoneId = ZoneId.of("Asia/Seoul")

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(KST)
}
