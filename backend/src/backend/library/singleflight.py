"""같은 키로 동시에 들어온 호출을 하나로 합친다(single-flight).

`ttl_cache`는 이 일을 캐시 안에서 한다. 분봉 저장소처럼 **통째로 버리지 않고 새 봉만 이어 붙이는**
곳은 `ttl_cache`를 쓸 수 없어 따로 둔다 — 같은 종목을 여러 명이 동시에 열면 각자 브로커를 불렀다.

선두 스레드만 `fn`을 부르고, 뒤따라온 스레드는 **락이 아니라 결과를** 기다린다. 선두가 느려도
그 뒤로 줄이 생기지 않는다. 선두가 실패하면 같은 예외를 함께 받는다 — 재시도는 부르는 쪽이 정한다.
"""

from collections.abc import Callable, Hashable
from concurrent.futures import Future
from threading import Lock
from typing import TypeVar

R = TypeVar("R")


class SingleFlight:
    def __init__(self) -> None:
        self._lock = Lock()
        self._inflight: dict[Hashable, Future] = {}

    def do(self, key: Hashable, fn: Callable[[], R]) -> R:
        with self._lock:
            pending = self._inflight.get(key)
            leader = pending is None
            if leader:
                pending = self._inflight[key] = Future()
        if not leader:
            return pending.result()
        try:
            result = fn()
        except BaseException as e:
            with self._lock:
                del self._inflight[key]
            pending.set_exception(e)
            raise
        with self._lock:
            del self._inflight[key]
        pending.set_result(result)
        return result
