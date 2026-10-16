package judozoo

import jakarta.servlet.http.Cookie
import judozoo.auth.presentation.LoginGateFilter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 컨트롤러 슬라이스 테스트의 로그인 — Python이 secret `test-secret`으로 발급한 실제 쿠키(사용자 id 7).
 *
 * 쓰는 테스트는 `@Import(LoggedIn.ClockAtSigning::class)`와 `@TestPropertySource(properties = [LoggedIn.SECRET])`를 붙인다.
 */
object LoggedIn {
    const val SECRET = "judozoo.session-secret=test-secret"

    val cookie = Cookie(
        LoginGateFilter.COOKIE_NAME,
        "eyJ1c2VyIjogeyJpZCI6IDcsICJlbWFpbCI6ICJhQGV4YW1wbGUuY29tIn0sICJfc3RhdGVfZ29vZ2xlX3giOiB7ImRhdGEiOiAxfX0=" +
            ".atDSsQ._chmEutnIautJuu1n_0keYIpTb0",
    )

    /** 쿠키가 살아 있는 시각(서명 시각)에 멈춘 시계. */
    @TestConfiguration
    class ClockAtSigning {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.ofEpochSecond(1_792_070_321), ZoneOffset.UTC)
    }
}
