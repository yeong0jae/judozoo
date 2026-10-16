package judozoo.auth.presentation

import judozoo.JudozooProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableConfigurationProperties(JudozooProperties::class)
class AuthWebConfig(private val currentUser: CurrentUserArgumentResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += currentUser
    }

    @Bean
    fun sessionCookies(properties: JudozooProperties, objectMapper: ObjectMapper, clock: Clock) = SessionCookies(properties, objectMapper, clock)
}
