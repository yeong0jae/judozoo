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
            # 수급은 코스피만 연다
            "/api/market/KOSPI/investor/sessions",
            "/api/market/KOSPI/investor/daily",
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
            # 시세·차트는 열었지만 투자자 수급은 닫은 채다 — 경로가 한 겹 차이라 섞이기 쉽다.
            # 코스피 수급만 예외로 열려 있어, 코스닥과 선물이 딸려 나가지 않는지 함께 본다.
            "/api/market/KOSDAQ/investor/sessions",
            "/api/market/KOSDAQ/investor/daily",
            "/api/market/futures/KOSPI/investor/sessions",
            "/api/market/futures/KOSPI/investor/daily",
            # 가공한 판단 결과들
            # 종목 시그널은 열었지만 지수 시그널은 닫은 채다 — 경로가 비슷해 섞이기 쉽다
            "/api/leading-stocks/market-signal-events",
        ],
    )
    def test_상세와_가공_결과는_막힌다(self, path):
        assert not is_public(path)

    def test_허용목록에_없는_새_경로는_기본이_차단이다(self):
        assert not is_public("/api/leading-stocks/whatever-comes-next")

    def test_공개_경로를_앞에_붙여도_뚫리지_않는다(self):
        assert not is_public("/api/market/kospi/../futures/kospi/quote")
