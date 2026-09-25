"""거래대금 상위를 언제까지 들고 있는가 — 장이 멈춘 동안은 다시 물을 이유가 없다."""

from datetime import datetime

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
