import time
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest

from backend.library.cache import is_empty, ttl_cache


class Test같은_인자로_다시_부르면:
    def test_계산하지_않고_캐시된_값을_준다(self):
        호출횟수 = 0

        @ttl_cache("테스트_기본", ttl_seconds=60)
        def 조회(코드: str) -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            return f"결과-{코드}"

        assert 조회("005930") == "결과-005930"
        assert 조회("005930") == "결과-005930"
        assert 호출횟수 == 1

    def test_인자가_다르면_따로_계산한다(self):
        호출횟수 = 0

        @ttl_cache("테스트_키분리", ttl_seconds=60)
        def 조회(코드: str) -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            return 코드

        조회("005930")
        조회("000660")

        assert 호출횟수 == 2


class TestTTL이_지나면:
    def test_다시_계산한다(self):
        호출횟수 = 0

        @ttl_cache("테스트_ttl", ttl_seconds=0.1)
        def 조회() -> int:
            nonlocal 호출횟수
            호출횟수 += 1
            return 호출횟수

        조회()
        time.sleep(0.15)
        조회()

        assert 호출횟수 == 2


class Test빈_응답은:
    """키움 거래대금 상위를 빈 리스트로 캐싱하면 후속 폴링이 TTL 동안 빈 결과를 재사용한다."""

    def test_캐시하지_않아_다음_호출에서_다시_시도한다(self):
        호출횟수 = 0
        응답: list[str] = []

        @ttl_cache("테스트_빈응답", ttl_seconds=60, skip_if=is_empty)
        def 조회() -> list[str]:
            nonlocal 호출횟수
            호출횟수 += 1
            return 응답

        assert 조회() == []
        assert 조회() == []
        assert 호출횟수 == 2, "빈 응답이 캐시되면 재시도 기회가 사라진다"

    def test_값이_채워지면_그때부터는_캐시한다(self):
        호출횟수 = 0
        응답: list[str] = []

        @ttl_cache("테스트_빈응답_회복", ttl_seconds=60, skip_if=is_empty)
        def 조회() -> list[str]:
            nonlocal 호출횟수
            호출횟수 += 1
            return list(응답)

        조회()
        응답.append("005930")
        조회()
        조회()

        assert 호출횟수 == 2


class Test최대_크기를_넘으면:
    def test_오래된_항목부터_밀려난다(self):
        호출횟수 = 0

        @ttl_cache("테스트_maxsize", ttl_seconds=60, maxsize=2)
        def 조회(코드: str) -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            return 코드

        조회("A")
        조회("B")
        조회("C")  # A가 밀려난다
        조회("A")  # 다시 계산

        assert 호출횟수 == 4


class Test만료_순간에_여러_스레드가_동시에_물으면:
    """TTL 5초에 화면 폴링도 5초라 경계에서 실제로 겹친다."""

    def test_한_번만_계산하고_나머지는_그_결과를_받는다(self):
        호출횟수 = 0
        출발선 = Barrier(4)

        @ttl_cache("테스트_동시미스", ttl_seconds=60)
        def 느린_조회(code: str) -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            time.sleep(0.05)      # 뒤따라온 스레드가 진행 중인 것을 보게 한다
            return f"값:{code}"

        def 부른다():
            출발선.wait()
            return 느린_조회("005930")

        with ThreadPoolExecutor(max_workers=4) as pool:
            결과들 = [f.result() for f in [pool.submit(부른다) for _ in range(4)]]

        assert 호출횟수 == 1
        assert 결과들 == ["값:005930"] * 4

    def test_키가_다르면_따로_계산한다(self):
        호출횟수 = 0
        출발선 = Barrier(4)

        @ttl_cache("테스트_동시미스_다른키", ttl_seconds=60)
        def 느린_조회(code: str) -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            time.sleep(0.05)
            return f"값:{code}"

        def 부른다(code: str):
            출발선.wait()
            return 느린_조회(code)

        with ThreadPoolExecutor(max_workers=4) as pool:
            코드들 = ["000660", "005930", "000660", "005930"]
            결과들 = [f.result() for f in [pool.submit(부른다, c) for c in 코드들]]

        assert 호출횟수 == 2
        assert 결과들 == [f"값:{c}" for c in 코드들]

    def test_선두가_실패하면_대기자도_같은_예외를_받는다(self):
        """재시도는 호출부가 정한다 — 캐시가 대신 삼키면 실패가 보이지 않는다."""
        호출횟수 = 0
        출발선 = Barrier(3)

        @ttl_cache("테스트_동시미스_예외", ttl_seconds=60)
        def 터지는_조회() -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            time.sleep(0.05)
            raise RuntimeError("브로커 오류")

        def 부른다():
            출발선.wait()
            with pytest.raises(RuntimeError, match="브로커 오류"):
                터지는_조회()

        with ThreadPoolExecutor(max_workers=3) as pool:
            for f in [pool.submit(부른다) for _ in range(3)]:
                f.result()

        assert 호출횟수 == 1

    def test_실패한_뒤에는_다시_부를_수_있다(self):
        """진행 중 표시를 안 지우면 그 키가 영영 막힌다."""
        호출횟수 = 0

        @ttl_cache("테스트_동시미스_회복", ttl_seconds=60)
        def 한_번만_터진다() -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            if 호출횟수 == 1:
                raise RuntimeError("첫 시도 실패")
            return "성공"

        with pytest.raises(RuntimeError):
            한_번만_터진다()

        assert 한_번만_터진다() == "성공"
        assert 호출횟수 == 2


class Test미리_갱신하면:
    """장중 폴러가 만료 전에 갈아 끼운다 — 화면은 늘 캐시에서 받는다."""

    def test_캐시에_값이_있어도_새로_불러_갈아_끼운다(self):
        호출횟수 = 0

        @ttl_cache("테스트_갱신_교체", ttl_seconds=60)
        def 조회() -> int:
            nonlocal 호출횟수
            호출횟수 += 1
            return 호출횟수

        조회()
        조회.refresh()

        assert 조회() == 2
        assert 호출횟수 == 2

    def test_갈아_끼우면_수명이_다시_시작된다(self):
        호출횟수 = 0

        @ttl_cache("테스트_갱신_수명", ttl_seconds=0.2)
        def 조회() -> int:
            nonlocal 호출횟수
            호출횟수 += 1
            return 호출횟수

        조회()
        time.sleep(0.15)
        조회.refresh()
        time.sleep(0.15)      # 처음 넣은 시점으로는 만료됐을 시각

        assert 조회() == 2
        assert 호출횟수 == 2

    def test_실패하면_기존_값을_남긴다(self):
        실패시킨다 = False

        @ttl_cache("테스트_갱신_실패", ttl_seconds=60)
        def 조회() -> str:
            if 실패시킨다:
                raise RuntimeError("브로커 오류")
            return "기존"

        조회()
        실패시킨다 = True
        with pytest.raises(RuntimeError):
            조회.refresh()

        assert 조회() == "기존"

    def test_갱신이_진행_중이면_비어_있는_캐시를_물은_쪽은_그_결과를_기다린다(self):
        호출횟수 = 0
        시작됨 = Barrier(2)

        @ttl_cache("테스트_갱신_합류", ttl_seconds=60)
        def 느린_조회() -> str:
            nonlocal 호출횟수
            호출횟수 += 1
            시작됨.wait()
            time.sleep(0.05)
            return "새 값"

        with ThreadPoolExecutor(max_workers=2) as pool:
            갱신 = pool.submit(느린_조회.refresh)
            시작됨.wait()
            조회 = pool.submit(느린_조회)
            assert 갱신.result() == 조회.result() == "새 값"

        assert 호출횟수 == 1
