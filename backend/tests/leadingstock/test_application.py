"""거래대금 상위를 언제까지 들고 있는가 — 장이 멈춘 동안은 다시 물을 이유가 없다."""

from datetime import date, datetime

from backend.leadingstock import application
from backend.leadingstock.domain import LeadingStockSnapshot


def 장_상태(monkeypatch, 휴장: bool, 거래시간: bool, 지금: datetime) -> None:
    monkeypatch.setattr("backend.market.calendar.market_status", lambda: (휴장, 거래시간))
    monkeypatch.setattr(application, "now", lambda: 지금)


class Test거래대금_상위_보관_기간:
    def test_장중에는_15초만_들고_있는다(self, monkeypatch):
        장_상태(monkeypatch, 휴장=False, 거래시간=True, 지금=datetime(2026, 9, 25, 10, 0))

        assert application._pool_ttl() == 15

    def test_마감_뒤에_받은_값은_다음날_장_시작까지_간다(self, monkeypatch):
        장_상태(monkeypatch, 휴장=False, 거래시간=False, 지금=datetime(2026, 9, 25, 20, 30))

        assert application._pool_ttl() == 11.5 * 3600

    def test_새벽에_받은_값은_그날_장_시작까지_간다(self, monkeypatch):
        장_상태(monkeypatch, 휴장=False, 거래시간=False, 지금=datetime(2026, 9, 25, 6, 0))

        assert application._pool_ttl() == 2 * 3600

    def test_휴장일에는_낮에_받아도_다음날_장_시작까지_간다(self, monkeypatch):
        장_상태(monkeypatch, 휴장=True, 거래시간=True, 지금=datetime(2026, 9, 24, 17, 10))

        assert application._pool_ttl() == 14 * 3600 + 50 * 60


class Test장이_멈춘_동안_조회하면:
    def test_한_번_받은_거래대금_상위를_다시_부르지_않는다(self, monkeypatch):
        장_상태(monkeypatch, 휴장=False, 거래시간=False, 지금=datetime(2026, 9, 25, 21, 0))
        호출횟수 = 0

        def 키움(count: int) -> list[LeadingStockSnapshot]:
            nonlocal 호출횟수
            호출횟수 += 1
            return [
                LeadingStockSnapshot(
                    stock_code="005930", stock_name="삼성전자", current_price=70_000,
                    price_change_rate=3.0, trading_value_rank=1, accumulated_trading_value=10**12,
                )
            ]

        monkeypatch.setattr(application.kiwoom_market, "fetch_top_trading_value_stocks", 키움)

        # 등락률이 다르면 후보 캐시는 따로지만, 그 밑의 거래대금 상위는 하나다
        application.find_candidate_stocks(0.0)
        application.find_candidate_stocks(-12.0)
        application.find_candidate_stocks(5.0)

        assert 호출횟수 == 1


def 분봉(날짜: date, 시: int, 분: int = 0):
    from backend.leadingstock.domain import MinuteCandle

    return MinuteCandle(
        date_time=datetime(날짜.year, 날짜.month, 날짜.day, 시, 분), open_price=100, high_price=100,
        low_price=100, close_price=100, volume=1, trading_value=100,
    )


class Test최근_3거래일_분봉:
    """오늘은 토스, 지난 날은 키움 — 지난 날 조회가 오늘을 기준일로 부르면 오늘 봉이 4일 캐시에 굳는다."""

    오늘 = date(2026, 9, 28)   # 월요일 — 어제(일)는 휴장이라 키움이 금요일부터 채운다

    def 준비(self, monkeypatch, 키움_실패=False):
        from backend.leadingstock import intraday

        intraday.reset()
        monkeypatch.setattr(application, "today", lambda: self.오늘)
        monkeypatch.setattr(application, "now", lambda: datetime(2026, 9, 28, 10, 0))
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))
        기준일들 = []
        지난날 = {
            date(2026, 9, 27): [분봉(date(2026, 9, 25), 9), 분봉(date(2026, 9, 24), 19)],
            date(2026, 9, 24): [분봉(date(2026, 9, 24), 9), 분봉(date(2026, 9, 23), 19)],
            date(2026, 9, 23): [분봉(date(2026, 9, 23), 9), 분봉(date(2026, 9, 22), 19)],
        }

        def 키움(code, base):
            기준일들.append(base)
            return [] if 키움_실패 else 지난날.get(base, [])

        monkeypatch.setattr(application.kiwoom_market, "fetch_historical_minute_candles", 키움)
        monkeypatch.setattr(
            application.toss_candles, "fetch_today_minute_candles", lambda code, since=None: [분봉(self.오늘, 9)]
        )
        return 기준일들

    def test_오늘_봉에_지난_두_거래일을_이어_붙인다(self, monkeypatch):
        self.준비(monkeypatch)

        봉들 = application.minute_candles("005930", self.오늘)

        assert sorted({c.date_time.date() for c in 봉들}) == [date(2026, 9, 24), date(2026, 9, 25), self.오늘]

    def test_지난_날_조회의_기준일은_늘_오늘보다_앞이다(self, monkeypatch):
        기준일들 = self.준비(monkeypatch)

        application.minute_candles("005930", self.오늘)

        assert 기준일들 and all(d < self.오늘 for d in 기준일들)

    def test_지난_날_조회가_실패해도_오늘을_기준일로_부르지_않는다(self, monkeypatch):
        기준일들 = self.준비(monkeypatch, 키움_실패=True)

        봉들 = application.minute_candles("005930", self.오늘)

        assert all(d < self.오늘 for d in 기준일들)
        assert {c.date_time.date() for c in 봉들} == {self.오늘}

    def test_저장소에_있는_종목은_토스를_직접_부르지_않는다(self, monkeypatch):
        from backend.leadingstock import intraday

        self.준비(monkeypatch)
        monkeypatch.setattr(intraday, "today", lambda: self.오늘)
        intraday.sync(["005930"])      # 저장소를 한 번 채운다 — 토스 한 번
        불림 = []
        monkeypatch.setattr(
            application.toss_candles, "fetch_today_minute_candles",
            lambda code, since=None: 불림.append(code) or [],
        )

        application.minute_candles("005930", self.오늘)

        assert 불림 == []
        intraday.reset()


class Test장_밖의_당일_분봉:
    """장 밖엔 저장소가 비어 요청이 토스로 간다 — 값이 안 바뀌는 동안은 다시 부르지 않는다."""

    def 준비(self, monkeypatch, 지금: datetime, 휴장: bool = False) -> list:
        from backend.leadingstock import intraday

        intraday.reset()
        monkeypatch.setattr(application, "now", lambda: 지금)
        monkeypatch.setattr(application, "today", lambda: 지금.date())
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (휴장, 8 <= 지금.hour < 20))
        불림 = []
        monkeypatch.setattr(
            application.toss_candles, "fetch_today_minute_candles",
            lambda code, since=None: 불림.append(code) or [분봉(지금.date(), 9)],
        )
        return 불림

    def test_마감_뒤에_받은_값은_다음날_장_시작까지_간다(self, monkeypatch):
        self.준비(monkeypatch, datetime(2026, 9, 28, 21, 0))

        assert application._today_minutes_ttl() == 11 * 3600

    def test_마감_직후_몇_분은_마지막_봉이_굳을_때까지_짧게_둔다(self, monkeypatch):
        self.준비(monkeypatch, datetime(2026, 9, 28, 20, 2))

        assert application._today_minutes_ttl() == 30

    def test_장중에는_30초만_들고_있는다(self, monkeypatch):
        self.준비(monkeypatch, datetime(2026, 9, 28, 14, 0))

        assert application._today_minutes_ttl() == 30

    def test_마감_뒤_두_번째_조회는_토스를_부르지_않는다(self, monkeypatch):
        불림 = self.준비(monkeypatch, datetime(2026, 9, 28, 21, 0))

        application._today_minute_candles("005930")
        application._today_minute_candles("005930")

        assert 불림 == ["005930"]

    def test_휴장일에는_토스를_부르지_않는다(self, monkeypatch):
        불림 = self.준비(monkeypatch, datetime(2026, 9, 27, 14, 0), 휴장=True)

        assert application._today_minute_candles("005930") == []
        assert 불림 == []

    def test_장_시작_전에는_토스를_부르지_않는다(self, monkeypatch):
        불림 = self.준비(monkeypatch, datetime(2026, 9, 28, 7, 30))

        assert application._today_minute_candles("005930") == []
        assert 불림 == []
