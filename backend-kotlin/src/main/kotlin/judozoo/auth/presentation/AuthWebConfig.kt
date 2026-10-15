package judozoo.auth.presentation

import judozoo.JudozooProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 로그인 관문이 세션 키(`judozoo.session-secret`)를 읽는다 — 웹 슬라이스 테스트에도 함께 실리도록 여기서 켠다. */
@Configuration
@EnableConfigurationProperties(JudozooProperties::class)
class AuthWebConfig(private val currentUser: CurrentUserArgumentResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += currentUser
    }
}
