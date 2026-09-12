"""TTL 캐시 데코레이터.

Kotlin의 `@Cacheable` + Caffeine에 대응한다. 인프로세스로 유지한다 —
인스턴스가 하나뿐이라 공유할 대상이 없고, TTL이 5초라 재시작 후 보존할 가치도 없다.

`@Cacheable(unless = "#result == null")`에 해당하는 문법이 Python에는 없으므로
`skip_if`로 명시한다. 특히 **빈 응답을 캐싱하면 안 되는** 케이스가 있다 —
키움 거래대금 상위 응답을 빈 리스트로 캐싱하면 후속 폴링이 TTL 동안 빈 결과를 재사용한다.
"""

from collections.abc import Callable, Hashable
from functools import wraps
from threading import Lock
from typing import Any, ParamSpec, TypeVar

from cachetools import TTLCache

P = ParamSpec("P")
R = TypeVar("R")

# 이름 → 캐시. 운영 중 상태를 들여다보거나 테스트에서 비우기 위해 노출한다.
_caches: dict[str, TTLCache] = {}
_locks: dict[str, Lock] = {}


def ttl_cache(
    name: str,
    ttl_seconds: float = 60.0,
    maxsize: int = 100,
    key: Callable[..., Hashable] | None = None,
    skip_if: Callable[[Any], bool] | None = None,
) -> Callable[[Callable[P, R]], Callable[P, R]]:
    """
    :param name: 캐시 이름. Kotlin `@Cacheable("...")`의 이름을 그대로 쓴다.
    :param ttl_seconds: 항목 수명.
    :param maxsize: 최대 엔트리 수. 초과 시 오래된 항목부터 밀려난다.
    :param key: 인자 → 캐시 키. 기본은 위치·키워드 인자 전체.
    :param skip_if: 결과가 이 조건을 만족하면 **저장하지 않는다**.
    """
    cache = _caches.setdefault(name, TTLCache(maxsize=maxsize, ttl=ttl_seconds))
    lock = _locks.setdefault(name, Lock())

    def decorator(fn: Callable[P, R]) -> Callable[P, R]:
        @wraps(fn)
        def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            cache_key = key(*args, **kwargs) if key else (args, tuple(sorted(kwargs.items())))
            with lock:
                if cache_key in cache:
                    return cache[cache_key]

            # 계산은 락 밖에서 한다. 같은 키에 대한 중복 계산이 생길 수 있지만,
            # 외부 API 호출을 락 안에서 붙잡고 있는 것보다 낫다.
            result = fn(*args, **kwargs)

            if skip_if is None or not skip_if(result):
                with lock:
                    cache[cache_key] = result
            return result

        wrapper.cache_name = name  # type: ignore[attr-defined]
        return wrapper

    return decorator


def clear_all() -> None:
    """테스트 격리용. 운영 코드에서 부르지 않는다."""
    for cache, lock in ((_caches[n], _locks[n]) for n in _caches):
        with lock:
            cache.clear()


def is_empty(result: Any) -> bool:
    """`skip_if`로 자주 쓰는 조건 — None이거나 비어 있으면 캐싱하지 않는다."""
    if result is None:
        return True
    if isinstance(result, (list, tuple, dict, set, str)):
        return len(result) == 0
    return False
