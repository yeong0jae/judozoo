package judozoo.library.brokertoken

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface BrokerTokenRepository : JpaRepository<BrokerToken, Long> {
    fun findByProvider(provider: String): BrokerToken?

    @Modifying
    @Query("delete from BrokerToken t where t.provider = :provider")
    fun deleteByProvider(provider: String): Int
}
