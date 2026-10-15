package judozoo.library

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@Scheduled`를 켠다. **같은 작업이 Python에서도 돌면 안 된다** — 기능을 옮기는 커밋에서 Python `register`를 끈다(028).
 *
 * `judozoo.scheduling.enabled=false`면 끈다 — 통합 테스트가 앱을 띄울 때 폴러가 실제 브로커·야후를 부르지 않게.
 */
@Configuration
@ConditionalOnProperty("judozoo.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig
