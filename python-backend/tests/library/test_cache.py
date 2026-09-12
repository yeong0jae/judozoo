import time

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
