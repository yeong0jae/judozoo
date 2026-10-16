package judozoo.stock.application

import judozoo.library.KST
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * 카탈로그를 언제 갱신할지. 기동 때, 그리고 매일 07:40.
 *
 * **07:40인 이유** — 개장은 NXT 프리마켓 08:00이라, 그 뒤에 갱신하면 신규 상장 종목이 프리마켓 동안 필터에서 빠진다.
 * KIS 마스터 파일은 **전날 저녁(18:55 KST 무렵)에 만들어져** 더 당겨도 손해가 없다.
 *
 * 스케줄링을 끄면(통합 테스트) 기동 때 갱신도 하지 않는다 — 마스터 파일을 실제로 받으러 나가지 않게.
 */
@Component
@ConditionalOnProperty("judozoo.scheduling.enabled", matchIfMissing = true)
class StockCatalogScheduler(private val catalog: StockCatalog, private val clock: Clock) {
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(cron = "0 40 7 * * *", zone = "Asia/Seoul")
    fun refresh() = catalog.refresh(LocalDate.now(clock.withZone(KST)))
}
