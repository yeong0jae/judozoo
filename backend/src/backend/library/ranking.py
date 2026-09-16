"""두 축을 함께 보는 순위 계산.

어느 도메인에도 매이지 않는다 — 무엇이 두 축인지는 부르는 쪽이 정한다.
"""

from collections.abc import Callable, Sequence
from typing import TypeVar

T = TypeVar("T")


def _percentiles(items: Sequence[T], key: Callable[[T], float]) -> list[float]:
    """큰 값이 1.0, 가장 작은 값이 1/n. 0을 주지 않는 건 기하평균이 통째로 0이 되지 않게 하려는 것.

    동점이면 먼저 온 항목이 앞선다 — 같은 입력이면 같은 순서가 나와야 한다.
    """
    n = len(items)
    order = sorted(range(n), key=lambda i: (-key(items[i]), i))
    result = [0.0] * n
    for place, i in enumerate(order):
        result[i] = (n - place) / n
    return result


def top_balanced(
    items: Sequence[T],
    first: Callable[[T], float],
    second: Callable[[T], float],
    count: int,
    first_weight: float = 0.5,
) -> list[T]:
    """두 축 **백분위의 (가중) 기하평균**이 높은 순으로 `count`개.

    값의 크기가 아니라 "이 무리에서 몇 등인가"만 본다. 한 축에 극단값이 하나 끼면
    크기를 그대로 쓰는 방식(정규화·로그 가중)은 그 하나가 축 전체를 눌러 나머지를
    구분하지 못하게 되는데, 백분위는 그 영향을 받지 않는다.

    기하평균이라 한쪽이 낮으면 함께 깎인다 — **두 축이 모두 높은 것**만 위로 온다.

    `first_weight`는 첫 축에 주는 무게다(0.5면 대등). 어느 쪽을 얼마나 무겁게 볼지는
    부르는 쪽이 정한다 — 여기는 그 뜻을 모른다.
    """
    if not items or count <= 0:
        return []
    firsts = _percentiles(items, first)
    seconds = _percentiles(items, second)
    scores = [
        f**first_weight * s ** (1 - first_weight)
        for f, s in zip(firsts, seconds, strict=True)
    ]
    order = sorted(range(len(items)), key=lambda i: (-scores[i], i))
    return [items[i] for i in order[:count]]
