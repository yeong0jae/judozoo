package judozoo.stock.application

import judozoo.library.KST
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

@Component
@ConditionalOnProperty("judozoo.scheduling.enabled", matchIfMissing = true)
class StockCatalogScheduler(private val catalog: StockCatalog, private val clock: Clock) {
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(cron = "0 40 7 * * *", zone = "Asia/Seoul")
    fun refresh() = catalog.refresh(LocalDate.now(clock.withZone(KST)))
}
