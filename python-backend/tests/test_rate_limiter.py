import time

import pytest

from backend.library.rate_limiter import RateLimiter, RateLimitTimeout


class Test허용량_안에서:
    def test_한도만큼은_대기_없이_통과한다(self):
        limiter = RateLimiter("테스트", permits_per_period=5)

        started = time.monotonic()
        for _ in range(5):
            limiter.acquire()

        assert time.monotonic() - started < 0.05


class Test허용량을_넘으면:
    def test_다음_주기까지_기다렸다가_통과한다(self):
        # 주기를 짧게 잡아 테스트가 오래 걸리지 않게 한다.
        limiter = RateLimiter("테스트", permits_per_period=2, period_seconds=0.2)
        limiter.acquire()
        limiter.acquire()

        started = time.monotonic()
        limiter.acquire()  # 세 번째 — 토큰이 없어 대기해야 한다
        elapsed = time.monotonic() - started

        assert elapsed >= 0.05, "대기 없이 통과하면 한도를 넘긴 것"

    def test_타임아웃_안에_확보하지_못하면_예외를_던진다(self):
        limiter = RateLimiter(
            "테스트", permits_per_period=1, period_seconds=10.0, timeout_seconds=0.1
        )
        limiter.acquire()

        with pytest.raises(RateLimitTimeout):
            limiter.acquire()


class Test잘못된_사용:
    def test_버킷_용량보다_큰_허가는_거부한다(self):
        limiter = RateLimiter("테스트", permits_per_period=3)

        with pytest.raises(ValueError):
            limiter.acquire(permits=4)

    def test_허용량은_1_이상이어야_한다(self):
        with pytest.raises(ValueError):
            RateLimiter("테스트", permits_per_period=0)
