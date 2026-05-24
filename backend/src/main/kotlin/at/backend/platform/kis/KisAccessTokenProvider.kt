package at.backend.platform.kis

import at.backend.platform.kis.client.KisAuthClient

/**
 * KIS 액세스 토큰 발급/제공. 캐시·만료·재발급은 [KisTokenRegistry]에 위임하여 동일 appKey를 쓰는
 * 다른 KisAuthClient 인스턴스와 공유한다(메인 / real-quotation이 같은 키일 때 1회만 발급).
 *
 * @Component 아님 — 메인 / real-quotation 두 호출처가 각각 자기 (appKey, KisAuthClient) 조합으로
 * 인스턴스화하고 동일 KisTokenRegistry를 주입받는다.
 */
class KisAccessTokenProvider(
    private val appKey: String,
    private val authClient: KisAuthClient,
    private val registry: KisTokenRegistry,
) {
    val token: String
        get() = registry.getOrIssue(appKey) { authClient.issueToken().accessToken }
}
