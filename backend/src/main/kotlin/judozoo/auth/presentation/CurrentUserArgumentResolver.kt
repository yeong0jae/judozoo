package judozoo.auth.presentation

import jakarta.servlet.http.HttpServletRequest
import judozoo.auth.domain.CurrentUser
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException

@Component
class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): CurrentUser? {
        val user = webRequest.getNativeRequest(HttpServletRequest::class.java)?.getAttribute(LoginGateFilter.CURRENT_USER) as CurrentUser?
        if (user == null && !parameter.isOptional) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED")
        return user
    }
}
