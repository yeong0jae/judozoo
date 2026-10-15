package judozoo.library

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@Scheduled`를 켠다. **같은 작업이 Python에서도 돌면 안 된다** — 기능을 옮기는 커밋에서 Python `register`를 끈다(028).
 */
@Configuration
@EnableScheduling
class SchedulingConfig
