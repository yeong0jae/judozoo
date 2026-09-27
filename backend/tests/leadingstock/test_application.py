"""거래대금 상위를 언제까지 들고 있는가 — 장이 멈춘 동안은 다시 물을 이유가 없다."""

from datetime import date, datetime, timedelta

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


class Test최근_3거래일_분봉_달력_없이:
    """달력이 모를 때의 예전 방식 — 지난 날 조회가 오늘을 기준일로 부르면 오늘 봉이 4일 캐시에 굳는다."""

    오늘 = date(2026, 9, 28)   # 월요일 — 어제(일)는 휴장이라 키움이 금요일부터 채운다

    def 준비(self, monkeypatch, 키움_실패=False):
        from backend.leadingstock import intraday, minute_archive

        intraday.reset()
        minute_archive.reset()
        monkeypatch.setattr(application, "today", lambda: self.오늘)
        monkeypatch.setattr(application, "now", lambda: datetime(2026, 9, 28, 10, 0))
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))
        monkeypatch.setattr("backend.market.calendar.previous_open_day", lambda on: None)
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
        monkeypatch.setattr(intraday, "now", lambda: datetime(2026, 9, 28, 10, 0))
        intraday.sync(["005930"])      # 저장소를 한 번 채운다 — 토스 한 번
        불림 = []
        monkeypatch.setattr(
            application.toss_candles, "fetch_today_minute_candles",
            lambda code, since=None: 불림.append(code) or [],
        )

        application.minute_candles("005930", self.오늘)

        assert 불림 == []
        intraday.reset()


class Test당일_분봉_읽기:
    """저장소에서 꺼내고 낡았으면 새 봉만 받는다. 마감이 굳은 뒤엔 확정본을 다음 장까지 쓴다."""

    def 준비(self, monkeypatch, 지금: datetime, 휴장: bool = False) -> dict:
        from backend.leadingstock import intraday, minute_archive

        intraday.reset()
        minute_archive.reset()
        상태 = {"지금": 지금, "불림": [], "실패": False}
        for 모듈 in (application, intraday):
            monkeypatch.setattr(모듈, "now", lambda: 상태["지금"])
            monkeypatch.setattr(모듈, "today", lambda: 상태["지금"].date())
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (휴장, 8 <= 상태["지금"].hour < 20))

        def 토스(code, since=None):
            상태["불림"].append(since)
            if 상태["실패"]:
                raise RuntimeError("토스 오류")
            return [분봉(상태["지금"].date(), 9)]

        monkeypatch.setattr(intraday.toss_candles, "fetch_today_minute_candles", 토스)
        return 상태

    def test_장중_30초_안에_받은_값이면_다시_부르지_않는다(self, monkeypatch):
        상태 = self.준비(monkeypatch, datetime(2026, 9, 28, 14, 0))
        application._today_minute_candles("035720")
        상태["지금"] += timedelta(seconds=20)

        application._today_minute_candles("035720")

        assert len(상태["불림"]) == 1

    def test_장중_30초가_지나면_새_봉만_받는다(self, monkeypatch):
        상태 = self.준비(monkeypatch, datetime(2026, 9, 28, 14, 0))
        application._today_minute_candles("035720")
        상태["지금"] += timedelta(seconds=40)

        application._today_minute_candles("035720")

        assert 상태["불림"][0] is None                # 처음엔 통째로
        assert 상태["불림"][1] is not None            # 그다음엔 마지막 봉 근처부터

    def test_마감이_굳은_뒤_한_번_받으면_다음_장까지_다시_부르지_않는다(self, monkeypatch):
        상태 = self.준비(monkeypatch, datetime(2026, 9, 28, 21, 0))
        application._today_minute_candles("035720")
        상태["지금"] = datetime(2026, 9, 28, 23, 59)

        application._today_minute_candles("035720")

        assert len(상태["불림"]) == 1

    def test_마감_전에_받은_값은_마감이_굳은_뒤_다시_받는다(self, monkeypatch):
        """20:00 직전 값은 마지막 봉이 덜 찼을 수 있다."""
        상태 = self.준비(monkeypatch, datetime(2026, 9, 28, 20, 0, 30))
        application._today_minute_candles("035720")
        상태["지금"] = datetime(2026, 9, 28, 20, 2)

        application._today_minute_candles("035720")

        assert len(상태["불림"]) == 2

    def test_마감_뒤_받다가_실패한_옛_봉은_확정본으로_넘기지_않는다(self, monkeypatch):
        from backend.leadingstock import minute_archive

        상태 = self.준비(monkeypatch, datetime(2026, 9, 28, 19, 0))
        application._today_minute_candles("035720")
        상태["지금"] = datetime(2026, 9, 28, 21, 0)
        상태["실패"] = True

        assert application._today_minute_candles("035720") != []       # 옛 봉이라도 보여준다
        assert minute_archive.get("035720", date(2026, 9, 28)) is None

    def test_휴장일에는_토스를_부르지_않는다(self, monkeypatch):
        상태 = self.준비(monkeypatch, datetime(2026, 9, 27, 14, 0), 휴장=True)

        assert application._today_minute_candles("005930") == []
        assert 상태["불림"] == []

    def test_장_시작_전에는_토스를_부르지_않는다(self, monkeypatch):
        상태 = self.준비(monkeypatch, datetime(2026, 9, 28, 7, 30))

        assert application._today_minute_candles("005930") == []
        assert 상태["불림"] == []


class Test최근_3거래일_분봉_달력으로:
    """거래일은 달력이 짚고, 지난 날은 보관소에서 먼저 찾는다 — 어제 봉을 키움에서 다시 받지 않게."""

    오늘 = date(2026, 9, 28)
    거래일 = [date(2026, 9, 22), date(2026, 9, 23), date(2026, 9, 25)]   # 9/24는 휴장이라 치자

    def 준비(self, monkeypatch) -> list:
        from backend.leadingstock import intraday, minute_archive

        intraday.reset()
        minute_archive.reset()
        monkeypatch.setattr(application, "today", lambda: self.오늘)
        monkeypatch.setattr(application, "now", lambda: datetime(2026, 9, 28, 10, 0))
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, True))
        monkeypatch.setattr(
            "backend.market.calendar.previous_open_day",
            lambda on: max((d for d in self.거래일 if d < on), default=None),
        )
        monkeypatch.setattr(
            application.toss_candles, "fetch_today_minute_candles", lambda code, since=None: [분봉(self.오늘, 9)]
        )
        기준일들: list = []

        def 키움(code, base):
            기준일들.append(base)
            앞 = max((d for d in self.거래일 if d < base), default=None)
            return [분봉(base, 9)] + ([분봉(앞, 19)] if 앞 else [])   # 그날 전체 + 전날 끝자락

        monkeypatch.setattr(application.kiwoom_market, "fetch_historical_minute_candles", 키움)
        return 기준일들

    def test_오늘과_달력의_직전_두_거래일을_잇는다(self, monkeypatch):
        self.준비(monkeypatch)

        봉들 = application.minute_candles("005930", self.오늘)

        assert sorted({c.date_time.date() for c in 봉들}) == [date(2026, 9, 23), date(2026, 9, 25), self.오늘]

    def test_보관소에_있는_날은_키움을_부르지_않는다(self, monkeypatch):
        from backend.leadingstock import minute_archive

        기준일들 = self.준비(monkeypatch)
        minute_archive.put("005930", date(2026, 9, 25), [분봉(date(2026, 9, 25), 9)])
        minute_archive.put("005930", date(2026, 9, 23), [분봉(date(2026, 9, 23), 9)])

        application.minute_candles("005930", self.오늘)

        assert 기준일들 == []

    def test_키움에서_받은_날은_보관소에_넣어_다음엔_부르지_않는다(self, monkeypatch):
        기준일들 = self.준비(monkeypatch)

        application.minute_candles("005930", self.오늘)
        첫_호출 = list(기준일들)
        application.minute_candles("005930", self.오늘)

        assert 첫_호출 == [date(2026, 9, 25), date(2026, 9, 23)]
        assert 기준일들 == 첫_호출

    def test_페이지_끝에서_잘린_날은_보관하지_않는다(self, monkeypatch):
        from backend.leadingstock import minute_archive

        self.준비(monkeypatch)

        application.minute_candles("005930", self.오늘)

        assert minute_archive.get("005930", date(2026, 9, 22)) is None   # 9/23 페이지 끝자락뿐이었다

    def test_지난_날짜를_보면_그날부터_세_거래일이다(self, monkeypatch):
        self.준비(monkeypatch)

        봉들 = application.minute_candles("005930", date(2026, 9, 25))

        assert sorted({c.date_time.date() for c in 봉들}) == self.거래일


class Test마감_확정:
    def test_확정된_오늘_봉을_보관소에_넘겨_마감_뒤엔_토스를_부르지_않는다(self, monkeypatch):
        from backend.leadingstock import intraday, minute_archive

        intraday.reset()
        minute_archive.reset()
        오늘 = date(2026, 9, 28)
        monkeypatch.setattr(application, "today", lambda: 오늘)
        monkeypatch.setattr(intraday, "today", lambda: 오늘)
        monkeypatch.setattr(application, "now", lambda: datetime(2026, 9, 28, 20, 1))
        monkeypatch.setattr("backend.market.calendar.market_status", lambda: (False, False))
        monkeypatch.setattr(application, "find_candidate_stocks", lambda rate: [종목("005930_AL")])
        불림 = []
        monkeypatch.setattr(
            application.toss_candles, "fetch_today_minute_candles",
            lambda code, since=None: 불림.append(code) or [분봉(오늘, 19, 59)],
        )

        assert application.settle_today_minutes() == 0
        불림.clear()

        assert [c.date_time for c in application._today_minute_candles("005930")] == [datetime(2026, 9, 28, 19, 59)]
        assert 불림 == []
        intraday.reset()
        minute_archive.reset()


def 종목(code: str) -> LeadingStockSnapshot:
    return LeadingStockSnapshot(
        stock_code=code, stock_name=code, current_price=1, price_change_rate=0.0,
        trading_value_rank=1, accumulated_trading_value=1,
    )
