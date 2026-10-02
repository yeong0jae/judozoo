"""캔들 컬렉션 — Kotlin `MinuteCandlesTest` / `DailyCandlesTest` 이관."""

from datetime import date, datetime, timedelta

import pytest

from backend.leadingstock.domain import DailyCandle, DailyCandles, MinuteCandle, MinuteCandles, SpikeDirection

기준 = datetime(2026, 6, 12, 9, 0)


def 고가봉(분: int, 고가: int) -> MinuteCandle:
    """시그널 판정은 고가만 쓴다 — 나머지 필드는 영향 없음."""
    return MinuteCandle(기준 + timedelta(minutes=분), 고가, 고가, 고가, 고가, 0, 0)


def 저가봉(분: int, 저가: int) -> MinuteCandle:
    """지지선 판정은 저가만 쓴다 — 나머지 필드는 영향 없음."""
    return MinuteCandle(기준 + timedelta(minutes=분), 저가, 저가, 저가, 저가, 0, 0)


def 대금봉(분: int, 거래대금: int) -> MinuteCandle:
    return MinuteCandle(기준 + timedelta(minutes=분), 0, 0, 0, 0, 0, 거래대금)


def 봉(분: int, *, 시가: int, 고가: int, 저가: int, 종가: int, 대금: int) -> MinuteCandle:
    """방향 판정용 — 꼬리를 직접 지정한다."""
    return MinuteCandle(기준 + timedelta(minutes=분), 시가, 고가, 저가, 종가, 0, 대금)


평탄봉 = [봉(분, 시가=100, 고가=100, 저가=100, 종가=100, 대금=100) for 분 in range(3)]


class Test당일_고가_돌파:
    def test_최고가를_전고점으로_잡고_잔여_상승률을_낸다(self):
        캔들 = MinuteCandles([고가봉(0, 100), 고가봉(1, 105), 고가봉(2, 108), 고가봉(3, 106), 고가봉(4, 102)])

        시그널 = 캔들.peak_signal(current_price=104)

        assert 시그널.peak_price == 108
        assert 시그널.peak_at == 기준 + timedelta(minutes=2)
        assert 시그널.gap_rate == pytest.approx((108 - 104) / 104 * 100, abs=0.001)

    def test_동일_최고가가_여러_번이면_처음_형성된_봉을_잡는다(self):
        캔들 = MinuteCandles([고가봉(0, 100), 고가봉(1, 108), 고가봉(2, 103), 고가봉(3, 105), 고가봉(4, 108)])

        assert 캔들.peak_signal(current_price=104).peak_at == 기준 + timedelta(minutes=1)

    def test_현재가가_고가에_도달하면_잔여_상승률은_0(self):
        캔들 = MinuteCandles([고가봉(0, 100), 고가봉(1, 105), 고가봉(2, 110)])

        assert 캔들.peak_signal(current_price=110).gap_rate == pytest.approx(0.0, abs=0.001)

    def test_입력이_시간_역순이어도_정렬해_같게_판정한다(self):
        캔들 = MinuteCandles([고가봉(4, 102), 고가봉(3, 106), 고가봉(2, 108), 고가봉(1, 105), 고가봉(0, 100)])

        시그널 = 캔들.peak_signal(current_price=104)

        assert (시그널.peak_price, 시그널.peak_at) == (108, 기준 + timedelta(minutes=2))

    def test_분봉이_없으면_None(self):
        assert MinuteCandles([]).peak_signal(current_price=1000) is None

    def test_현재가가_0_이하면_None(self):
        assert MinuteCandles([고가봉(0, 100), 고가봉(1, 105)]).peak_signal(current_price=0) is None


class Test거래대금_스파이크:
    def test_최신_봉이_직전_평균의_N배면_배율_N(self):
        캔들 = MinuteCandles([대금봉(0, 100), 대금봉(1, 100), 대금봉(2, 100), 대금봉(3, 500)])

        스파이크 = 캔들.volume_spike(baseline_bars=3)

        assert 스파이크.latest_trading_value == 500
        assert 스파이크.at == 기준 + timedelta(minutes=3)
        assert 스파이크.ratio == pytest.approx(5.0, abs=0.001)

    def test_직전_평균은_지정한_봉_수로_제한된다(self):
        캔들 = MinuteCandles([
            대금봉(0, 1000), 대금봉(1, 1000), 대금봉(2, 200), 대금봉(3, 200), 대금봉(4, 400),
        ])

        assert 캔들.volume_spike(baseline_bars=2).ratio == pytest.approx(2.0, abs=0.001)

    def test_봉이_하나뿐이면_None(self):
        assert MinuteCandles([대금봉(0, 100)]).volume_spike(baseline_bars=20) is None

    def test_직전_평균이_0이면_None(self):
        assert MinuteCandles([대금봉(0, 0), 대금봉(1, 500)]).volume_spike(baseline_bars=20) is None

    def test_양봉이면_매수_음봉이면_매도다(self):
        """차트 거래량 막대 색과 같다 — 빨강(종가 ≥ 시가)은 매수, 파랑은 매도."""
        양봉 = MinuteCandles([*평탄봉, 봉(3, 시가=100, 고가=120, 저가=99, 종가=118, 대금=500)])
        음봉 = MinuteCandles([*평탄봉, 봉(3, 시가=120, 고가=121, 저가=100, 종가=102, 대금=500)])

        assert 양봉.volume_spike(baseline_bars=3).direction is SpikeDirection.BUY
        assert 음봉.volume_spike(baseline_bars=3).direction is SpikeDirection.SELL

    def test_위로_찔렀다_되밀려도_양봉이면_매수다(self):
        # 시가 100 → 고가 112 → 종가 101. 고저 위치는 저가권이지만 막대가 빨강이다.
        되밀림 = MinuteCandles([*평탄봉, 봉(3, 시가=100, 고가=112, 저가=99, 종가=101, 대금=500)])

        assert 되밀림.volume_spike(baseline_bars=3).direction is SpikeDirection.BUY

    def test_고가_근처로_회복해도_음봉이면_매도다(self):
        회복 = MinuteCandles([*평탄봉, 봉(3, 시가=110, 고가=111, 저가=98, 종가=109, 대금=500)])

        assert 회복.volume_spike(baseline_bars=3).direction is SpikeDirection.SELL

    def test_종가가_시가와_같으면_차트처럼_매수다(self):
        보합 = MinuteCandles([*평탄봉, 봉(3, 시가=100, 고가=105, 저가=95, 종가=100, 대금=500)])
        한가격 = MinuteCandles([*평탄봉, 봉(3, 시가=100, 고가=100, 저가=100, 종가=100, 대금=500)])

        assert 보합.volume_spike(baseline_bars=3).direction is SpikeDirection.BUY
        assert 한가격.volume_spike(baseline_bars=3).direction is SpikeDirection.BUY

오늘 = date(2026, 6, 9)


def 일봉(on: date, 거래량: int) -> DailyCandle:
    return DailyCandle(on, 1000, 1000, 1000, 1000, 거래량, 0.0)


class Test상대거래량:
    def test_현재거래량을_별도로_받으면_캐시된_오늘_봉보다_우선한다(self):
        캔들 = DailyCandles([
            일봉(오늘, 100),
            일봉(오늘 - timedelta(days=1), 200),
            일봉(오늘 - timedelta(days=2), 200),
        ])

        assert 캔들.relative_volume_from(600, 오늘, 20) == 3.0
        assert 캔들.relative_volume_from(None, 오늘, 20) is None

    def test_당일_누적이_직전_평균의_몇_배인지_낸다(self):
        캔들 = DailyCandles([
            일봉(오늘, 300),
            일봉(오늘 - timedelta(days=1), 100),
            일봉(오늘 - timedelta(days=2), 100),
            일봉(오늘 - timedelta(days=3), 100),
        ])

        assert 캔들.relative_volume(오늘, 3) == 3.0

    def test_베이스라인은_당일을_빼고_최신_N거래일만_쓴다(self):
        캔들 = DailyCandles([
            일봉(오늘, 400),
            일봉(오늘 - timedelta(days=1), 200),
            일봉(오늘 - timedelta(days=2), 200),
            일봉(오늘 - timedelta(days=3), 1000),  # lookback 밖 — 무시돼야 한다
        ])

        assert 캔들.relative_volume(오늘, 2) == 2.0

    def test_당일_캔들이_없으면_None(self):
        캔들 = DailyCandles([일봉(오늘 - timedelta(days=1), 100), 일봉(오늘 - timedelta(days=2), 100)])

        assert 캔들.relative_volume(오늘, 20) is None

    def test_직전_거래일_데이터가_없으면_None(self):
        assert DailyCandles([일봉(오늘, 300)]).relative_volume(오늘, 20) is None


class Test지지선:
    """당일 저가 근접도 — 저항선(고가)의 대칭."""

    def test_최저가를_지지선으로_잡는다(self):
        시그널 = MinuteCandles([저가봉(0, 1000), 저가봉(1, 950), 저가봉(2, 980)]).trough_signal(1000)

        assert 시그널.trough_price == 950
        assert 시그널.trough_at == 기준 + timedelta(minutes=1)

    def test_저가까지_남은_하락률을_준다(self):
        시그널 = MinuteCandles([저가봉(0, 900)]).trough_signal(1000)

        assert 시그널.gap_rate == pytest.approx(10.0)

    def test_이미_저가에_닿았으면_0(self):
        assert MinuteCandles([저가봉(0, 1000)]).trough_signal(1000).gap_rate == pytest.approx(0.0)

    def test_저가를_깨고_내려갔으면_음수(self):
        assert MinuteCandles([저가봉(0, 1000)]).trough_signal(900).gap_rate < 0

    def test_같은_저가가_여럿이면_처음_봉을_잡는다(self):
        시그널 = MinuteCandles([저가봉(0, 950), 저가봉(5, 950)]).trough_signal(1000)

        assert 시그널.trough_at == 기준

    def test_봉이_없으면_시그널도_없다(self):
        assert MinuteCandles([]).trough_signal(1000) is None

    def test_현재가가_0_이하면_시그널이_없다(self):
        assert MinuteCandles([저가봉(0, 900)]).trough_signal(0) is None
