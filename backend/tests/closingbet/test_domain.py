"""모의 종가베팅 — 언제 무엇을 하고, 얼마에 사고팔며, 누가 이겼나."""

import random
from datetime import date, datetime, time, timedelta

import pytest

from backend.closingbet.domain import (
    BetRejected,
    Checks,
    Grade,
    Level,
    Moment,
    NicknameRejected,
    OpenDays,
    Outcome,
    Result,
    Results,
    Stake,
    Stakes,
    check_bet,
    check_nickname,
    check_rename,
    level_of,
    phase_at,
    random_nickname,
    reveal_at,
    sell_price,
    shares_for,
)
from backend.leadingstock.domain import DailyCandle, MinuteCandle

# 2026-10: 1(목) 2(금) 개장, 3(토)·4(일) 휴장, 5(월) 개장, 6(화) 개장
DAYS = OpenDays([date(2026, 9, 30), date(2026, 10, 1), date(2026, 10, 2), date(2026, 10, 5), date(2026, 10, 6)])
THU, FRI, SAT, MON = date(2026, 10, 1), date(2026, 10, 2), date(2026, 10, 3), date(2026, 10, 5)


def at(day: date, hh: int, mm: int = 0, ss: int = 0) -> datetime:
    return datetime.combine(day, time(hh, mm, ss))


def bar(day: date, hh: int, mm: int, high: int, low: int, volume: int = 10) -> MinuteCandle:
    return MinuteCandle(at(day, hh, mm), low, high, low, high, volume, high * volume)


def daily(day: date, high: int) -> DailyCandle:
    return DailyCandle(day, high, high, high, high, 1, 0.0)


class Test하루_네_칸:
    @pytest.mark.parametrize(
        ("hh", "mm", "moment"),
        [
            (7, 59, Moment.NIGHT),
            (8, 0, Moment.RESULT),
            (9, 4, Moment.RESULT),
            (9, 5, Moment.REVIEW),
            (14, 59, Moment.REVIEW),
            (15, 0, Moment.BET),
            (19, 59, Moment.BET),
            (20, 0, Moment.NIGHT),
            (23, 30, Moment.NIGHT),
        ],
    )
    def test_시각이_화면을_정한다(self, hh, mm, moment):
        assert phase_at(at(FRI, hh, mm), DAYS).moment is moment

    def test_20시_정각부터는_베팅을_받지_않는다(self):
        assert phase_at(at(FRI, 19, 59, 59), DAYS).accepts_bets
        assert not phase_at(at(FRI, 20, 0, 0), DAYS).accepts_bets

    def test_베팅_시간에는_오늘_판을_받고_어제_판을_복기한다(self):
        p = phase_at(at(FRI, 16, 42), DAYS)
        assert (p.betting, p.review, p.next_at) == (FRI, THU, at(FRI, 20))

    def test_결과_발표는_어제_판을_09시05분까지_공개한다(self):
        p = phase_at(at(FRI, 8, 3), DAYS)
        assert (p.betting, p.review, p.next_at) == (None, THU, at(FRI, 9, 5))

    def test_자정이_지나도_어젯밤_체결된_판을_기다린다(self):
        p = phase_at(at(FRI, 6, 0), DAYS)
        assert (p.moment, p.betting, p.review) == (Moment.NIGHT, THU, date(2026, 9, 30))

    def test_금요일_밤_판은_월요일_아침에_결과가_나온다(self):
        p = phase_at(at(FRI, 21, 0), DAYS)
        assert (p.betting, p.next_at) == (FRI, at(MON, 8))

    def test_휴장일에는_직전_판을_복기하고_다음_개장일_아침을_센다(self):
        p = phase_at(at(SAT, 12, 0), DAYS)
        assert (p.moment, p.betting, p.review, p.next_at) == (Moment.HOLIDAY, None, THU, at(MON, 8))

    def test_월요일_새벽은_금요일_판을_기다린다(self):
        p = phase_at(at(MON, 7, 0), DAYS)
        assert (p.moment, p.betting) == (Moment.NIGHT, FRI)


class Test베팅_규칙:
    BET = phase_at(at(FRI, 16, 0), DAYS)
    POOL = ["000660", "247540"]

    @pytest.mark.parametrize("amount", [100, 2_000, 10_000])
    def test_100만부터_1억까지_100만_단위로_건다(self, amount):
        assert check_bet(self.BET, amount, "000660", self.POOL, 412_500) >= 1

    @pytest.mark.parametrize("amount", [0, 50, 150, 10_100])
    def test_범위나_단위를_벗어나면_받지_않는다(self, amount):
        with pytest.raises(BetRejected):
            check_bet(self.BET, amount, "000660", self.POOL, 412_500)

    def test_목록에_없는_종목은_고를_수_없다(self):
        with pytest.raises(BetRejected, match="목록"):
            check_bet(self.BET, 1_000, "005930", self.POOL, 70_000)

    def test_베팅_시간_밖에는_받지_않는다(self):
        with pytest.raises(BetRejected, match="15:00"):
            check_bet(phase_at(at(FRI, 20, 0), DAYS), 1_000, "000660", self.POOL, 412_500)

    def test_주식_수는_금액을_가격으로_나눈_몫이다(self):
        assert shares_for(2_000, 184_500) == 108

    def test_한_주도_못_사면_베팅할_수_없다(self):
        with pytest.raises(BetRejected, match="한 주"):
            shares_for(100, 1_284_000)


class Test아침_5분_매도:
    def test_NXT_종목은_08시부터_5분_고가_저가_중간(self):
        bars = [bar(FRI, 7, 59, 999, 1), bar(FRI, 8, 0, 110, 100), bar(FRI, 8, 4, 120, 105), bar(FRI, 8, 5, 999, 1)]
        got = sell_price(bars, FRI, nxt_listed=True, buy_price=100)
        assert (got.price, got.window_start) == (110.0, at(FRI, 8))

    def test_NXT_비상장_종목은_09시부터(self):
        bars = [bar(FRI, 8, 0, 999, 1), bar(FRI, 9, 0, 201, 200)]
        assert sell_price(bars, FRI, nxt_listed=False, buy_price=100).price == 200.5

    def test_창에_체결이_없으면_첫_체결부터_5분으로_미룬다(self):
        bars = [bar(FRI, 9, 12, 130, 120), bar(FRI, 9, 16, 150, 110), bar(FRI, 9, 17, 999, 1)]
        got = sell_price(bars, FRI, nxt_listed=False, buy_price=100)
        assert (got.price, got.window_start) == (130.0, at(FRI, 9, 12))

    def test_거래량_없는_봉은_체결로_치지_않는다(self):
        bars = [bar(FRI, 9, 0, 999, 1, volume=0), bar(FRI, 9, 6, 102, 100)]
        assert sell_price(bars, FRI, nxt_listed=False, buy_price=100).window_start == at(FRI, 9, 6)

    def test_그날_끝까지_체결이_없으면_매수가로_0퍼센트(self):
        got = sell_price([], FRI, nxt_listed=True, buy_price=184_500)
        assert (got.price, got.window_start) == (184_500.0, None)
        assert Outcome.of(108, 184_500, got.price) == Outcome(0, 0.0)

    def test_수익은_주식_수와_가격_차이로_낸다(self):
        o = Outcome.of(108, 184_500, 188_375.5)
        assert o.pnl == 418_554
        assert o.rate == pytest.approx(2.1005, abs=1e-4)

    def test_결과는_매도_창이_끝나면_공개한다(self):
        assert reveal_at(FRI, nxt_listed=True) == at(FRI, 8, 5)
        assert reveal_at(FRI, nxt_listed=False) == at(FRI, 9, 5)


class Test종베_체크와_등급:
    def test_다섯_가지를_모두_채우면_S(self):
        c = Checks.of(price=412_500, high=414_000, foreign_net=2640, institution_net=910, market_late_net=740, level=Level.NEW_HIGH)
        assert (c.passed, c.grade) == (5, Grade.S)

    def test_고가에서_1_5퍼센트_넘게_밀리면_고가_마감이_아니다(self):
        assert Checks.of(985, 1000, 1, 1, 1, None).near_high
        assert not Checks.of(984, 1000, 1, 1, 1, None).near_high

    @pytest.mark.parametrize(("passed", "grade"), [(4, Grade.A), (3, Grade.B), (2, Grade.C), (0, Grade.C)])
    def test_채운_개수로_등급을_매긴다(self, passed, grade):
        flags = [1 if i < passed else -1 for i in range(4)]
        c = Checks.of(1000 if flags[0] > 0 else 900, 1000, flags[1], flags[2], flags[3], None)
        assert c.grade is grade

    def test_순매수가_0이면_샀다고_보지_않는다(self):
        assert not Checks.of(1000, 1000, 0, 0, 0, None).foreign


class Test신고가와_박스_돌파:
    def history(self, highs_by_age: dict[int, int], filler: int = 100) -> list[DailyCandle]:
        """age 1 = 어제. 나머지 날은 filler."""
        return [daily(FRI - timedelta(days=age), highs_by_age.get(age, filler)) for age in range(1, 300)]

    def test_고가가_52주_고가를_넘으면_신고가(self):
        assert level_of(self.history({200: 150}), today_high=151, today_close=140) is Level.NEW_HIGH

    def test_52주_고가에_못_미쳐도_종가가_60일_고가_위면_박스_돌파(self):
        assert level_of(self.history({200: 150, 30: 120}), today_high=130, today_close=121) is Level.BOX_BREAKOUT

    def test_60일_고가를_고가로만_넘고_종가가_못_지키면_아니다(self):
        assert level_of(self.history({200: 150, 30: 120}), today_high=130, today_close=120) is None

    def test_250거래일보다_오래된_고점은_보지_않는다(self):
        assert level_of(self.history({280: 500}), today_high=101, today_close=101) is Level.NEW_HIGH

    def test_과거_일봉이_없으면_판정하지_않는다(self):
        assert level_of([], today_high=100, today_close=100) is None


class Test판돈:
    def test_참여_수와_종목별_판돈을_센다(self):
        s = Stakes([Stake(1, "000660", 2_000, at(FRI, 15)), Stake(2, "000660", 500, at(FRI, 16)), Stake(3, "247540", 100, at(FRI, 17))])
        assert (s.total_man, s.players) == (2_600, 3)
        assert s.by_stock() == {"000660": 2_500, "247540": 100}
        assert s.crowd() == {"000660": 2, "247540": 1}


class Test랭킹:
    def result(self, user: int, pnl: int, rate: float, minute: int) -> Result:
        return Result(user, "000660", at(THU, 15, minute), Outcome(pnl, rate))

    def test_같은_값이면_공동_순위이고_다음_순위는_건너뛴다(self):
        rs = Results([self.result(1, 100, 1.0, 5), self.result(2, 100, 1.0, 1), self.result(3, 50, 2.0, 0)])
        assert [(x.rank, x.result.user_id) for x in rs.ranked(by_rate=False)] == [(1, 2), (1, 1), (3, 3)]

    def test_수익률_랭킹은_따로_매긴다(self):
        rs = Results([self.result(1, 100, 1.0, 0), self.result(2, 50, 2.0, 0)])
        assert [x.result.user_id for x in rs.ranked(by_rate=True)] == [2, 1]

    def test_TOP_5와_내_순위(self):
        rs = Results([self.result(u, 1000 - u, 1.0, 0) for u in range(1, 9)])
        assert [x.rank for x in rs.top(by_rate=False)] == [1, 2, 3, 4, 5]
        assert rs.rank_of(8, by_rate=False).rank == 8
        assert rs.rank_of(99, by_rate=False) is None


class Test닉네임:
    @pytest.mark.parametrize("nick", ["수달", "Brave01", "용감한수달이다1"])
    def test_2자부터_10자까지_한글_영문_숫자(self, nick):
        assert check_nickname(nick, banned=[]) == nick

    @pytest.mark.parametrize("nick", ["달", "열한글자닉네임입니다요", "용감한 수달", "수달!", "🦦수달"])
    def test_규칙을_벗어나면_받지_않는다(self, nick):
        with pytest.raises(NicknameRejected):
            check_nickname(nick, banned=[])

    def test_금칙어가_들어_있으면_받지_않는다(self):
        with pytest.raises(NicknameRejected, match="단어"):
            check_nickname("진짜운영자", banned=["운영자"])

    def test_바꾼_뒤_7일은_다시_못_바꾼다(self):
        renamed = at(THU, 10)
        with pytest.raises(NicknameRejected, match="10/8"):
            check_rename(renamed, renamed + timedelta(days=6, hours=23))
        check_rename(renamed, renamed + timedelta(days=7))

    def test_처음_받은_랜덤_닉네임은_바로_바꿀_수_있다(self):
        check_rename(None, at(THU, 10))

    def test_랜덤_닉네임은_겹치지_않는다(self):
        rng = random.Random(1)
        taken: set[str] = set()
        for _ in range(150):
            nick = random_nickname(taken, rng)
            assert nick not in taken
            check_nickname(nick, banned=[])
            taken.add(nick)
