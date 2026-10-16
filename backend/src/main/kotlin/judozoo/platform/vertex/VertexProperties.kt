package judozoo.platform.vertex

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `judozoo.vertex.*` — Gemini on Vertex AI(026). 인증은 ADC라 키가 없다. 전부 비밀값이 아니다. */
@ConfigurationProperties("judozoo.vertex")
data class VertexProperties(
    val baseUrl: String = "https://aiplatform.googleapis.com",
    val project: String = "trading-496508",
    /** global이 리전 고정보다 10% 싸다. 데이터 위치 요건이 없다 */
    val location: String = "global",
    val model: String = "gemini-3.8-flash",
    /** 하루 전체 생성 상한(국내·해외 합). 넘으면 그날은 멈춘다 */
    val dailyLimit: Int = 150,
    /** 생성 응답은 정상일 때도 30초 안팎이라 읽기는 60초. 연결은 5초 */
    val readTimeout: Duration = Duration.ofSeconds(60),
    val connectTimeout: Duration = Duration.ofSeconds(5),
)
