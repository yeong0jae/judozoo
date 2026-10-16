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

/**
 * 컨트롤러가 `user: CurrentUser`로 로그인한 사람을 받는다. [LoginGateFilter]가 이미 읽어 둔 값이다.
 *
 * `CurrentUser?`면 비로그인도 받는다(공개 경로에서 필드만 나눌 때). `CurrentUser`인데 없으면 401 —
 * 관문이 이미 막지만, 공개 경로에 잘못 붙여도 노출이 아니라 거절로 끝나게.
 */
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
