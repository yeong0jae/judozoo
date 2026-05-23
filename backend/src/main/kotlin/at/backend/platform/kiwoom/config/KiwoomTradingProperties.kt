package at.backend.platform.kiwoom.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile

/**
 * Kiwoom 트레이딩 전용 설정. `kiwoom` profile에서만 binding.
 *
 * - [accountNo]: TradingCycle.accountNo 분류용 식별자. Kiwoom API 호출에는 토큰이 계좌를 묶고
 *   있어 전달하지 않지만, DB·UI 표시에는 필요.
 * - [wsUrl]: 실시간 주문체결 통보용 WebSocket 엔드포인트. `wss://api.kiwoom.com:10000/api/dostk/websocket`.
 * - [dmstStexTp]: 주문 거래소 라우팅. `KRX` | `NXT` | `SOR`.
 */
@ConfigurationProperties(prefix = "kiwoom.trading")
@Profile("kiwoom")
data class KiwoomTradingProperties(
    val accountNo: String,
    val wsUrl: String,
    val dmstStexTp: String,
)
