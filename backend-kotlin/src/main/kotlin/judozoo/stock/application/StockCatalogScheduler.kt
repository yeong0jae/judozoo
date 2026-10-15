package judozoo.stock.application

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 카탈로그를 언제 다시 읽을지. 기동 때, 그리고 매일 07:50.
 *
 * **07:50인 이유** — Python이 07:40에 마스터 파일을 받아 테이블을 갈아끼운다(개장은 NXT 프리마켓 08:00).
 * 그 뒤에 읽어야 신규 상장 종목이 프리마켓 동안 빠지지 않는다.
 */
@Component
class StockCatalogScheduler(private val catalog: StockCatalog) {
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(cron = "0 50 7 * * *", zone = "Asia/Seoul")
    fun reload() = catalog.reload()
}
