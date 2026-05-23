package at.backend.trading.application.broker

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

/**
 * broker × env profile 조합 검증. 잘못된 조합으로 띄우면 컨텍스트 시작 실패.
 *
 * 유효 조합:
 *   - kis,vts
 *   - kis,real
 *   - kiwoom (real 동반 가능, vts 불가)
 *
 * 두 broker가 동시 active인 경우 Spring이 BrokerTradingClient 빈을 2개 발견해 자체적으로
 * UnsatisfiedDependencyException을 던지지만, 명시적 검증이 디버깅 메시지가 명확하다.
 */
@Component
class BrokerActivationGuard(
    private val environment: Environment,
) {

    @PostConstruct
    fun verify() {
        val active = environment.activeProfiles.toSet()
        val brokers = BROKER_PROFILES.intersect(active)
        val envs = ENV_PROFILES.intersect(active)

        require(brokers.size == 1) {
            "broker profile은 정확히 1개여야 함 (활성: $brokers, 후보: $BROKER_PROFILES)"
        }
        val broker = brokers.first()

        // test profile은 application-test.yaml이 KIS 값을 직접 제공하므로 env 검증을 면제한다.
        if ("test" in active) return

        when (broker) {
            "kis" -> require(envs.size == 1) {
                "kis broker는 env profile(vts/real) 정확히 1개 필요 (활성: $envs)"
            }
            "kiwoom" -> require("vts" !in envs) {
                "kiwoom broker는 vts profile과 조합 불가 (Kiwoom 모의 미운영)"
            }
        }
    }

    companion object {
        private val BROKER_PROFILES = setOf("kis", "kiwoom")
        private val ENV_PROFILES = setOf("vts", "real")
    }
}
