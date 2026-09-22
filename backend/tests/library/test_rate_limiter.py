import time

import pytest

from backend.library import metrics
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


def 대기_합계(limiter: str) -> float:
    return metrics.RATE_LIMITER_WAIT.labels(limiter=limiter)._sum.get() or 0.0


def 포기_횟수(limiter: str) -> float:
    return metrics.RATE_LIMITER_TIMEOUTS.labels(limiter=limiter)._value.get() or 0.0


class Test대기_계측:
    def test_기다린_시간이_대기_분포에_쌓인다(self):
        """CPU도 디스크도 한가한데 화면이 느린 구간을 가리키는 유일한 지표다."""
        limiter = RateLimiter("계측-대기", permits_per_period=1, period_seconds=0.2)
        limiter.acquire()

        before = 대기_합계("계측-대기")
        limiter.acquire()  # 토큰이 없어 한 주기를 기다린다

        assert 대기_합계("계측-대기") >= before + 0.05

    def test_한도에_안_걸린_호출도_건수로_남는다(self):
        """0초 대기가 안 쌓이면 '한가해서 빠른 것'과 '호출 자체가 없는 것'이 구분되지 않는다."""
        limiter = RateLimiter("계측-무대기", permits_per_period=5)

        limiter.acquire()

        assert metrics.RATE_LIMITER_WAIT.labels(limiter="계측-무대기")._sum.get() is not None

    def test_포기한_호출은_카운터로만_세고_대기_분포는_건드리지_않는다(self):
        """못 얻은 시간을 섞으면 성공 대기 분포가 타임아웃 상한에 붙어 왜곡된다."""
        limiter = RateLimiter(
            "계측-포기", permits_per_period=1, period_seconds=10.0, timeout_seconds=0.05
        )
        limiter.acquire()

        대기_before = 대기_합계("계측-포기")
        포기_before = 포기_횟수("계측-포기")
        with pytest.raises(RateLimitTimeout):
            limiter.acquire()

        assert 포기_횟수("계측-포기") == 포기_before + 1
        assert 대기_합계("계측-포기") == 대기_before
