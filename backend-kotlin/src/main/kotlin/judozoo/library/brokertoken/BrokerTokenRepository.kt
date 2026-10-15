package judozoo.library.brokertoken

import org.springframework.data.jpa.repository.JpaRepository

/** 읽기만 한다. 저장·삭제 메서드를 부르지 않는다 — Python이 발급 주인이다(028). */
interface BrokerTokenRepository : JpaRepository<BrokerToken, Long> {
    fun findByProvider(provider: String): BrokerToken?
}
