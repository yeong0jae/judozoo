import threading
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


class Test주기가_시간대마다_바뀌면:
    def test_그_순간의_주기로_허가를_낸다(self):
        주기 = [0.05]
        limiter = RateLimiter("테스트_가변", permits_per_period=1, period_seconds=lambda: 주기[0])

        limiter.acquire()
        started = time.monotonic()
        limiter.acquire()
        빠를_때 = time.monotonic() - started

        주기[0] = 0.3                 # 피크타임에 들어섰다
        limiter.acquire()             # 남은 토큰을 비운다
        started = time.monotonic()
        limiter.acquire()
        느릴_때 = time.monotonic() - started

        assert 빠를_때 < 0.1
        assert 느릴_때 > 0.2


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


class Test대기_중인_스레드_수:
    def test_줄_선_스레드를_세고_빠져나가면_0으로_돌아온다(self):
        """borrowed만으로는 40개가 잡힌 이유를 모른다 — 이 값이 그중 '리미터 탓'을 떼어낸다."""
        limiter = RateLimiter(
            "계측-동시", permits_per_period=1, period_seconds=0.3, timeout_seconds=30.0
        )
        gauge = metrics.RATE_LIMITER_WAITING.labels(limiter="계측-동시")

        threads = [threading.Thread(target=limiter.acquire) for _ in range(5)]
        for t in threads:
            t.start()
        time.sleep(0.15)  # 토큰 하나만 나간 시점 — 나머지는 자고 있다
        대기중 = gauge._value.get()

        for t in threads:
            t.join()

        assert 대기중 >= 2, "동시에 몰린 스레드가 대기로 안 잡히면 포화를 못 본다"
        assert gauge._value.get() == 0, "빠져나간 스레드가 남으면 값이 영영 떠 있는다"

    def test_포기하고_나가도_대기_수가_새지_않는다(self):
        """예외 경로에서 감소를 빠뜨리면 게이지가 실제보다 높은 채로 굳는다."""
        limiter = RateLimiter(
            "계측-누수", permits_per_period=1, period_seconds=10.0, timeout_seconds=0.05
        )
        limiter.acquire()

        with pytest.raises(RateLimitTimeout):
            limiter.acquire()

        assert metrics.RATE_LIMITER_WAITING.labels(limiter="계측-누수")._value.get() == 0
