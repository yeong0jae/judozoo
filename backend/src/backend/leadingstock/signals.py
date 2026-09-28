"""시그널 상태기 — 전이는 **상태가 바뀌는 첫 순간에만** 한 번 잡는다(라이징 엣지).

임계선 근처 떨림은 히스테리시스로 흡수한다. 진입 임계와 해제 임계를 따로 둬,
한 번 켜지면 충분히 물러나야 다시 켜진다.
"""

from dataclasses import dataclass
from enum import Enum


class SignalEventType(Enum):
    """적재 대상 시그널 전이 종류.

    돌파·임박은 2026-09-13에 제거했다 — 지지·저항 화면이 근접도를 실시간으로 보여주므로
    "가까워졌다"를 사건으로 또 적재할 이유가 없어졌다. 종목 반등·꺾임(20이평 돌파)은
    2026-09-28에 제거했다. DB의 옛 행은 이력으로 남아 있다.
    """

    VOLUME_SPIKE = "VOLUME_SPIKE"              # 1분 거래대금 배율이 임계를 처음 넘긴 순간


class MarketSignalType(Enum):
    """시장(지수) 시그널 종류."""

    NET_BUY_LEVEL = "NET_BUY_LEVEL"      # 투자자 누적 순매수가 단계(조/천억)를 넘은 전이
    NET_FLOW_TURN = "NET_FLOW_TURN"      # 누적 순매수 흐름이 정점에서 되돌려 방향이 꺾인 전환
    MA_REBOUND = "MA_REBOUND"            # 지수 5분봉 종가가 20이평을 아래→위로 뚫은 반등
    MA_BREAKDOWN = "MA_BREAKDOWN"        # 지수 5분봉 종가가 20이평을 위→아래로 뚫은 꺾임


class NetTradeSide(Enum):
    """누적 순매수의 방향. 중립(0)은 방향 없음으로 본다."""

    BUY = "BUY"
    SELL = "SELL"

    def opposite(self) -> "NetTradeSide":
        return NetTradeSide.SELL if self is NetTradeSide.BUY else NetTradeSide.BUY


class InvestorType(Enum):
    """시장 순매수 시그널의 주체."""

    FOREIGN = "FOREIGN"
    INSTITUTION = "INSTITUTION"
    INDIVIDUAL = "INDIVIDUAL"


@dataclass(frozen=True)
class SignalReading:
    """한 종목의 현재 시그널 측정값(한 폴링 시점). 값이 없으면 None."""

    spike_ratio: float | None    # 최신 1분봉 거래대금 배율 — 임계 미달이면 None


_SPIKE_FIRE_RATIO = 2.5
_SPIKE_RESET_RATIO = 2.0


@dataclass(frozen=True)
class SignalState:
    """한 종목의 직전 시그널 상태.

    `advance`로 새 측정값을 받아 "이번에 발생한 전이"와 다음 상태를 함께 돌려준다.
    """

    spiking: bool = False

    def advance(self, reading: SignalReading) -> tuple[list[SignalEventType], "SignalState"]:
        events: list[SignalEventType] = []
        spiking = self.spiking

        # 스파이크: 임계 진입 시 발화, 해제 임계 아래로 식으면 해제.
        ratio = reading.spike_ratio
        if not spiking and ratio is not None and ratio >= _SPIKE_FIRE_RATIO:
            events.append(SignalEventType.VOLUME_SPIKE)
            spiking = True
        elif spiking and (ratio is None or ratio < _SPIKE_RESET_RATIO):
            spiking = False

        return events, SignalState(spiking)


@dataclass(frozen=True)
class FlowTurn:
    """순매수 흐름 전환 — 전환 방향과 되돌리기 직전의 정점 누적(억원, 부호 포함)."""

    to: NetTradeSide
    extreme_eok: int


@dataclass(frozen=True)
class InvestorFlowState:
    """한 (시장·투자자)의 누적 순매수 흐름 전환 감지.

    진행 방향의 정점을 기억하고, 정점 대비 임계 이상 반대로 되돌리면 전환으로 본다.
    (예: 외인 순매도 정점 −6.8조 → −6.5조면 0.3조 매수 유입 = 전환 기미)
    전환 후엔 반대 방향 정점을 새로 추적하므로 다시 꺾이면 재전환을 잡는다.
    """

    side: NetTradeSide | None = None
    extreme_eok: int = 0

    def advance(self, net_eok: int, reversal_eok: int) -> tuple[FlowTurn | None, "InvestorFlowState"]:
        cur_side = NetTradeSide.BUY if net_eok > 0 else (NetTradeSide.SELL if net_eok < 0 else None)

        # 방향이 아직 없으면 잡힐 때까지 정점만 따라간다.
        if self.side is None:
            return None, InvestorFlowState(cur_side, net_eok)

        direction = 1 if self.side is NetTradeSide.BUY else -1
        cur_prog = net_eok * direction     # 추적 방향으로의 진행 정도
        ext_prog = self.extreme_eok * direction

        # 진행 방향으로 더 극단 → 정점 갱신, 전환 없음.
        if cur_prog >= ext_prog:
            return None, InvestorFlowState(self.side, net_eok)

        if ext_prog - cur_prog >= reversal_eok:
            to = self.side.opposite()
            # 전환 — 정점을 실어 보내고 반대 방향 정점을 새로 시작한다.
            return FlowTurn(to, self.extreme_eok), InvestorFlowState(to, net_eok)

        # 임계 미달 — 정점 유지, 전환 없음.
        return None, InvestorFlowState(self.side, self.extreme_eok)


@dataclass(frozen=True)
class NetBuyTransition:
    """레벨 전이 한 건 — 어느 방향으로 몇 단계에 도달했는지."""

    side: NetTradeSide
    level: int


@dataclass(frozen=True)
class InvestorNetBuyState:
    """한 (시장·투자자)의 누적 순매수 단계 상태.

    단계를 새로 넘나드는 **순간에만** 한 번씩 전이를 잡는다.
    올라갈 때는 단계선에서 승급, 내려올 때는 `단계선 − 완충` 아래로 떨어져야 강등한다.
    방향이 바뀌면(0 통과 포함) 단계를 리셋하고 반대편부터 다시 센다.
    """

    side: NetTradeSide | None = None
    level: int = 0

    def advance(
        self, net_eok: int, step_eok: int, buffer_eok: int
    ) -> tuple[NetBuyTransition | None, "InvestorNetBuyState"]:
        new_side = NetTradeSide.BUY if net_eok > 0 else (NetTradeSide.SELL if net_eok < 0 else None)
        # 방향이 그대로면 현재 단계에서 이어 판정, 바뀌면 0부터 다시.
        base_level = self.level if new_side == self.side else 0
        new_level = (
            0 if new_side is None
            else _step_with_hysteresis(base_level, abs(net_eok), step_eok, buffer_eok)
        )

        transition = (
            NetBuyTransition(new_side, new_level)
            if new_side is not None and new_level >= 1 and new_level != base_level
            else None
        )
        return transition, InvestorNetBuyState(new_side, new_level)


def _step_with_hysteresis(current: int, magnitude: int, step: int, buffer: int) -> int:
    """승급은 단계선에서, 강등은 `단계선 − 완충`에서."""
    s = current
    while magnitude >= (s + 1) * step:
        s += 1
    while s > 0 and magnitude < s * step - buffer:
        s -= 1
    return s
