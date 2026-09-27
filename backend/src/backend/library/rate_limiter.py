"""공유 토큰 버킷 레이트 리미터.

Kotlin의 Resilience4j `RateLimiter`에 대응한다. 핵심 시맨틱 두 가지를 보존한다.

1. **한 버킷을 공유한다** — 폴러·상세조회·돌파감지가 각자 리미터를 가지면
   합산이 브로커 한도를 넘는다. 클라이언트당 인스턴스 하나를 공유해야 한다.
2. **한도 도달 시 거부가 아니라 대기한다** — 폴러가 죽는 것보다 늦는 게 낫다.
   다만 무한정 기다리지는 않고 timeout을 넘기면 예외를 던진다.

**주기를 함수로 주면 시간대마다 속도가 바뀐다.** 키움은 09~10시에만 한도가 낮아진다.
채울 때마다 그 순간의 주기로 계산하므로, 경계를 넘는 즉시 새 속도가 적용된다.
"""

import threading
import time
from collections.abc import Callable

from backend.library import metrics


class RateLimitTimeout(RuntimeError):
    """timeout 안에 허가를 얻지 못했다."""


class RateLimiter:
    def __init__(
        self,
        name: str,
        permits_per_period: int,
        period_seconds: float | Callable[[], float] = 1.0,
        timeout_seconds: float = 20.0,
    ) -> None:
        if permits_per_period <= 0:
            raise ValueError("permits_per_period는 1 이상이어야 한다")
        self.name = name
        self._capacity = float(permits_per_period)
        self._period_of = period_seconds if callable(period_seconds) else (lambda: period_seconds)
        self._timeout = timeout_seconds
        self._tokens = float(permits_per_period)
        self._updated_at = time.monotonic()
        self._lock = threading.Lock()

    def acquire(self, permits: int = 1) -> None:
        """허가를 얻을 때까지 블로킹한다. timeout 초과 시 `RateLimitTimeout`.

        **대기 시간을 잰다.** `http_client_request_duration_seconds`는 리미터 대기와
        브로커 왕복을 합쳐 재므로, 느린 원인이 우리 쪽 줄서기인지 상대 서버인지 가르지
        못한다. 여기서 대기만 따로 남겨야 그 둘이 분리된다.

        타임아웃은 히스토그램에 넣지 않고 카운터로만 센다 — 허가를 **못 얻은** 시간을
        섞으면 성공 대기 분포가 상한(timeout)에 붙어 왜곡된다.
        """
        if permits <= 0:
            raise ValueError("permits는 1 이상이어야 한다")
        if permits > self._capacity:
            raise ValueError(f"permits({permits})가 버킷 용량({self._capacity})보다 크다")

        started = time.monotonic()
        deadline = started + self._timeout
        # 대기 중인 스레드 수. 잠든 동안에도 스레드풀 슬롯을 쥐고 있어서,
        # 이 값이 곧 "지금 이 리미터 때문에 묶여 있는 스레드"다.
        with metrics.RATE_LIMITER_WAITING.labels(limiter=self.name).track_inprogress():
            while True:
                with self._lock:
                    self._refill()
                    if self._tokens >= permits:
                        self._tokens -= permits
                        metrics.RATE_LIMITER_WAIT.labels(limiter=self.name).observe(
                            time.monotonic() - started
                        )
                        return
                    shortfall = permits - self._tokens
                    wait = shortfall * self._period_of() / self._capacity

                if time.monotonic() + wait > deadline:
                    metrics.RATE_LIMITER_TIMEOUTS.labels(limiter=self.name).inc()
                    raise RateLimitTimeout(
                        f"[{self.name}] {self._timeout}초 안에 허가 {permits}건을 얻지 못했다"
                    )
                time.sleep(wait)

    def _refill(self) -> None:
        """마지막 갱신 이후 경과 시간만큼 토큰을 채운다. 호출자가 락을 쥔 상태여야 한다."""
        now = time.monotonic()
        elapsed = now - self._updated_at
        if elapsed <= 0:
            return
        self._tokens = min(self._capacity, self._tokens + elapsed * self._capacity / self._period_of())
        self._updated_at = now
