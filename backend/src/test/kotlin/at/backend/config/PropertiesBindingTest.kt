package at.backend.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class PropertiesBindingTest {

    @Autowired
    lateinit var kisProperties: KisProperties

    @Autowired
    lateinit var tradingProperties: TradingProperties

    @Test
    fun `KisProperties 바인딩 확인`() {
        assertNotNull(kisProperties.appKey)
        assertNotNull(kisProperties.baseUrl)
    }

    @Test
    fun `TradingProperties 바인딩 확인`() {
        assertNotNull(tradingProperties.sellCostRate)
        assertNotNull(tradingProperties.marketCloseTime)
    }
}
