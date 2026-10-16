package judozoo.library

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@Scheduled`를 켠다. **인스턴스를 늘리면 작업이 그 수만큼 돈다** — DB에 쓰는 작업은 한 곳에서만 돌게 해야 한다.
 *
 * `judozoo.scheduling.enabled=false`면 끈다 — 통합 테스트가 앱을 띄울 때 폴러가 실제 브로커·야후를 부르지 않게.
 */
@Configuration
@ConditionalOnProperty("judozoo.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig
