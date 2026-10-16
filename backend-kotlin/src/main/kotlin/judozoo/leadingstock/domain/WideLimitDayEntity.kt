package judozoo.leadingstock.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 그날 +30%를 넘은 적이 있는 종목 — 제한폭이 넓은 날(상장 첫날)이라는 표시.
 *
 * 넘은 순간 한 번 넣고 그날 내내 순위 계산이 본다. 키움 거래대금 순위에는 고가·전일 종가가 없어
 * 재시작 뒤 다시 알아볼 방법이 없으므로 DB에 둔다. **Python도 같은 테이블에 넣는다** — 같은 행이면 유니크 키가 막는다.
 */
@Entity
@Table(name = "wide_limit_day")
class WideLimitDayEntity(
    @Column(name = "trade_date")
    val tradeDate: LocalDate,
    @Column(name = "stock_code")
    val stockCode: String,
    @Column(name = "created_at")
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null
}
