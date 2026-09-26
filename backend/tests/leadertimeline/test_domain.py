"""어느 분을 찍는가 — 세션 안이고 쉬는 구간이 아니면."""

from datetime import time

import pytest

from backend.leadertimeline.domain import captures
from backend.market.calendar import Region


class Test국내_찍는_분:
    @pytest.mark.parametrize("at", [time(8, 0), time(8, 49), time(9, 0), time(15, 29), time(15, 40), time(19, 59)])
    def test_세션_안은_찍는다(self, at):
        assert captures(Region.KR, at)

    @pytest.mark.parametrize("at", [time(8, 50), time(8, 59), time(15, 30), time(15, 39)])
    def test_장_사이_쉬는_구간은_찍지_않는다(self, at):
        assert not captures(Region.KR, at)

    @pytest.mark.parametrize("at", [time(7, 59), time(20, 0), time(23, 0)])
    def test_세션_밖은_찍지_않는다(self, at):
        assert not captures(Region.KR, at)


class Test해외_찍는_분:
    def test_프리마켓에서_정규장으로_쉬지_않고_이어진다(self):
        assert all(captures(Region.US, time(h, m)) for h in range(4, 16) for m in range(60))

    @pytest.mark.parametrize("at", [time(3, 59), time(16, 0)])
    def test_세션_밖은_찍지_않는다(self, at):
        assert not captures(Region.US, at)
