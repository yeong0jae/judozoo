import pytest

from backend.auth.gate import is_public


class Test로그인_없이_열어둔_경로:
    @pytest.mark.parametrize(
        "path",
        [
            "/api/leading-stocks/candidates",
            "/api/leading-stocks/leaders",
            "/api/overseas-leading-stocks/leaders",
            "/api/leading-stocks/signal-events",
            "/api/leading-stocks/breakout-radar",
            "/api/overseas-leading-stocks/ranking",
            "/api/market/calendar/status",
            "/api/market/kospi",
            "/api/market/kosdaq",
            "/api/market/nasdaq/quote",
            "/api/market/macro/quotes",
            "/api/market/futures/night/quote",
            "/api/market/futures/night/candles",
            # 지수·선물의 시세와 차트 — 수급만 로그인 뒤로 남긴다
            "/api/market/KOSPI/candles",
            "/api/market/futures/KOSPI/quote",
            "/api/market/futures/KOSDAQ/candles",
            "/api/market/nasdaq/candles",
            "/api/market/macro/candles",
            # 투자자 수급은 현물·선물 모두 연다
            "/api/market/KOSPI/investor/sessions",
            "/api/market/KOSDAQ/investor/daily",
            "/api/market/futures/KOSPI/investor/sessions",
            "/api/market/futures/KOSDAQ/investor/daily",
            "/api/market/investor/today",
            "/api/auth/google/callback",
        ],
    )
    def test_주도주_목록과_공개_지표는_누구나_본다(self, path):
        assert is_public(path)

    @pytest.mark.parametrize(
        "path",
        [
            "/api/market/kospi/",
            "/api/leading-stocks/candidates/",
            "/api/overseas-leading-stocks/ranking/",
            "/api/auth/google/callback/",
        ],
    )
    def test_끝의_빗금은_같은_경로로_본다(self, path):
        """허용목록 전체를 묶지 않으면 이 꼬리가 마지막 항목에만 걸린다."""
        assert is_public(path)


class Test로그인을_요구하는_경로:
    @pytest.mark.parametrize(
        "path",
        [
            # 목록은 열어도 종목 상세는 닫는다 — 국내·해외 모두
            "/api/leading-stocks/candidates/005930",
            "/api/leading-stocks/candidates/005930/minute-candles",
            "/api/overseas-leading-stocks/NAS/NVDA",
            "/api/overseas-leading-stocks/NAS/NVDA/daily-candles",
            # 시장 수급은 열었지만 **종목별** 수급은 종목 상세라 닫은 채다
            "/api/stocks/005930/investor/daily",
            # 시그널 화면에 속한 것들 — 종목 시그널 미리보기만 열고 나머지는 닫는다.
            # 경로가 비슷해 시장 수급을 열 때 함께 딸려 나가기 쉽다.
            "/api/leading-stocks/market-signal-events",
            "/api/leading-stocks/market/investor-net-buy",
        ],
    )
    def test_상세와_가공_결과는_막힌다(self, path):
        assert not is_public(path)

    def test_허용목록에_없는_새_경로는_기본이_차단이다(self):
        assert not is_public("/api/leading-stocks/whatever-comes-next")

    def test_공개_경로를_앞에_붙여도_뚫리지_않는다(self):
        assert not is_public("/api/market/kospi/../futures/kospi/quote")
