package judozoo.library

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** 지금 시각은 이 빈으로만 얻는다. 도메인은 시각을 파라미터로 받는다. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(KST)
}
