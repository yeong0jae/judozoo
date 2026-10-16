package judozoo.platform.yahoo

import org.springframework.boot.context.properties.ConfigurationProperties

/** `judozoo.yahoo.*`. 인증 없는 공개 엔드포인트라 주소뿐이다. */
@ConfigurationProperties("judozoo.yahoo")
data class YahooProperties(val baseUrl: String = "https://query1.finance.yahoo.com")
