"""시그널 상태기 — Kotlin `SignalStateTest` / `InvestorFlowStateTest` / `InvestorNetBuyStateTest` 이관."""

import pytest

from backend.leadingstock.signals import (
    FlowTurn,
    InvestorFlowState,
    InvestorNetBuyState,
    NetBuyTransition,
    NetTradeSide,
    SignalEventType,
    SignalReading,
    SignalState,
)


def 측정(스파이크=None) -> SignalReading:
    return SignalReading(spike_ratio=스파이크)


def 흘려보내기(*측정들) -> tuple[list[SignalEventType], SignalState]:
    """측정값을 차례로 흘려보내고 **마지막** 전이 결과만 본다."""
    state = SignalState()
    last: list[SignalEventType] = []
    for r in 측정들:
        last, state = state.advance(r)
    return last, state






class Test거래대금_스파이크:
    def test_배율이_임계_이상으로_처음_튀면_스파이크를_낸다(self):
        events, _ = SignalState().advance(측정(스파이크=4.0))

        assert events == [SignalEventType.VOLUME_SPIKE]

    def test_식지_않은_채_계속_높으면_다시_내지_않는다(self):
        events, _ = 흘려보내기(측정(스파이크=4.0), 측정(스파이크=3.5))

        assert events == []

    def test_해제_임계_아래로_식었다가_다시_넘기면_재발화한다(self):
        events, _ = 흘려보내기(측정(스파이크=4.0), 측정(스파이크=1.5), 측정(스파이크=3.2))

        assert events == [SignalEventType.VOLUME_SPIKE]


class Test순매수_흐름_전환:
    되돌림 = 2_000  # 0.2조

    def 흘리기(self, *누적들) -> FlowTurn | None:
        state = InvestorFlowState()
        last = None
        for net in 누적들:
            last, state = state.advance(net, self.되돌림)
        return last

    def test_정점에서_임계_이상_되돌리면_전환으로_본다(self):
        """외인 순매도 정점 −6.8조 → −6.5조(0.3조 매수 유입) = 전환 기미."""
        assert self.흘리기(-10_000, -68_000, -65_000) == FlowTurn(NetTradeSide.BUY, -68_000)

    def test_임계_미만_되돌림은_전환이_아니다(self):
        assert self.흘리기(-68_000, -67_000) is None

    def test_진행_방향으로_정점을_갱신하는_동안은_전환이_없다(self):
        assert self.흘리기(-30_000, -50_000, -68_000) is None

    def test_전환_후_반대_방향에서_다시_되돌리면_재전환을_잡는다(self):
        assert self.흘리기(-68_000, -65_000, -67_000) == FlowTurn(NetTradeSide.SELL, -65_000)

    def test_순매수_방향도_대칭으로_동작한다(self):
        assert self.흘리기(20_000, 50_000, 47_000) == FlowTurn(NetTradeSide.SELL, 50_000)


class Test순매수_단계:
    """코스피 기준 단계 1조(10,000억), 완충 1,000억."""

    단계 = 10_000
    완충 = 1_000

    def 흘리기(self, state: InvestorNetBuyState, net: int):
        return state.advance(net, self.단계, self.완충)

    def test_단계선에_닿으면_그_단계로_전이된다(self):
        전이, _ = self.흘리기(InvestorNetBuyState(), 10_000)

        assert 전이 == NetBuyTransition(NetTradeSide.BUY, 1)

    def test_단계선에_못_미치면_전이가_없다(self):
        전이, _ = self.흘리기(InvestorNetBuyState(), 9_000)

        assert 전이 is None

    def test_연이어_다음_단계선을_넘으면_다음_단계로(self):
        _, 일단계 = self.흘리기(InvestorNetBuyState(), 10_000)

        전이, _ = self.흘리기(일단계, 20_000)

        assert 전이 == NetBuyTransition(NetTradeSide.BUY, 2)

    def test_두_단계를_건너뛰어도_도달한_최종_단계만_전이된다(self):
        전이, _ = self.흘리기(InvestorNetBuyState(), 25_000)

        assert 전이 == NetBuyTransition(NetTradeSide.BUY, 2)

    def test_완충_안에서_오르내려도_같은_단계가_다시_발화하지_않는다(self):
        _, 이단계 = self.흘리기(InvestorNetBuyState(), 20_000)

        내림, 이후 = self.흘리기(이단계, 19_500)   # 19,000 위 → 유지
        오름, _ = self.흘리기(이후, 20_500)        # 다음 단계선 미달 → 유지

        assert (내림, 오름) == (None, None)

    def test_완충을_넘어_떨어지면_한_단계_강등된다(self):
        _, 이단계 = self.흘리기(InvestorNetBuyState(), 20_000)

        전이, _ = self.흘리기(이단계, 18_900)  # 19,000 아래

        assert 전이 == NetBuyTransition(NetTradeSide.BUY, 1)

    def test_중립으로_완전히_복귀하면_전이가_없다(self):
        _, 일단계 = self.흘리기(InvestorNetBuyState(), 10_000)

        전이, _ = self.흘리기(일단계, 0)

        assert 전이 is None

    def test_방향이_뒤집히면_단계를_리셋하고_반대편에서_다시_센다(self):
        매도, 매도이단계 = self.흘리기(InvestorNetBuyState(), -20_000)
        assert 매도 == NetBuyTransition(NetTradeSide.SELL, 2)

        매수, _ = self.흘리기(매도이단계, 12_000)

        assert 매수 == NetBuyTransition(NetTradeSide.BUY, 1)

    def test_순매도도_단계선에_닿으면_전이된다(self):
        전이, _ = self.흘리기(InvestorNetBuyState(), -10_000)

        assert 전이 == NetBuyTransition(NetTradeSide.SELL, 1)
