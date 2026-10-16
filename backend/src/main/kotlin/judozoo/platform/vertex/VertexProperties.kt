package judozoo.platform.vertex

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("judozoo.vertex")
data class VertexProperties(
    val baseUrl: String = "https://aiplatform.googleapis.com",
    val project: String = "trading-496508",
    val location: String = "global",
    val model: String = "gemini-3.8-flash",
    val dailyLimit: Int = 150,
    val readTimeout: Duration = Duration.ofSeconds(60),
    val connectTimeout: Duration = Duration.ofSeconds(5),
)
