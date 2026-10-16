package judozoo

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("judozoo")
data class JudozooProperties(
    val sessionSecret: String,
)
