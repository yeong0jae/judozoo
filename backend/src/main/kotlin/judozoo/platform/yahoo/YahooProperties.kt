package judozoo.platform.yahoo

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("judozoo.yahoo")
data class YahooProperties(val baseUrl: String = "https://query1.finance.yahoo.com")
