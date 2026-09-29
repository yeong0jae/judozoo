"""같은 키로 동시에 들어온 호출을 하나로 합친다."""

import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from backend.library.singleflight import SingleFlight


def 동시에(n: int, fn):
    """n개 스레드가 같은 순간에 fn을 부른다. 결과(또는 예외)를 순서대로 돌려준다."""
    with ThreadPoolExecutor(n) as pool:
        futures = [pool.submit(fn) for _ in range(n)]
        return [f.exception() or f.result() for f in futures]


class Test동시_호출:
    def test_같은_키면_한_번만_부르고_모두_같은_결과를_받는다(self):
        flight, 풀림, 호출 = SingleFlight(), threading.Event(), []

        def 느린_일():
            호출.append(1)
            풀림.wait(1)
            return "값"

        def 요청():
            return flight.do("AAPL", 느린_일)

        with ThreadPoolExecutor(5) as pool:
            futures = [pool.submit(요청) for _ in range(5)]
            threading.Event().wait(0.1)   # 다섯이 모두 들어올 틈
            풀림.set()
            결과 = [f.result() for f in futures]

        assert 호출 == [1]
        assert 결과 == ["값"] * 5

    def test_선두가_실패하면_기다리던_쪽도_같은_예외를_받는다(self):
        flight, 풀림 = SingleFlight(), threading.Event()

        def 터지는_일():
            풀림.wait(1)
            raise RuntimeError("브로커 오류")

        with ThreadPoolExecutor(3) as pool:
            futures = [pool.submit(flight.do, "AAPL", 터지는_일) for _ in range(3)]
            threading.Event().wait(0.1)
            풀림.set()
            오류 = [f.exception() for f in futures]

        assert all(isinstance(e, RuntimeError) for e in 오류)

    def test_키가_다르면_따로_부른다(self):
        flight, 호출 = SingleFlight(), []

        flight.do("AAPL", lambda: 호출.append("AAPL"))
        flight.do("MSFT", lambda: 호출.append("MSFT"))

        assert 호출 == ["AAPL", "MSFT"]

    def test_끝난_뒤에_온_호출은_새로_부른다(self):
        """합치는 건 **진행 중인** 호출뿐이다 — 결과를 들고 있지 않는다."""
        flight, 호출 = SingleFlight(), []

        flight.do("AAPL", lambda: 호출.append(1))
        flight.do("AAPL", lambda: 호출.append(2))

        assert 호출 == [1, 2]

    def test_실패한_뒤에는_다시_부를_수_있다(self):
        flight = SingleFlight()
        with pytest.raises(RuntimeError):
            flight.do("AAPL", lambda: (_ for _ in ()).throw(RuntimeError("오류")))

        assert flight.do("AAPL", lambda: "값") == "값"
