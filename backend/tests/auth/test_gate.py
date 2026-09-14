import pytest

from backend.auth.gate import is_public


class Test로그인_없이_열어둔_경로:
    @pytest.mark.parametrize(
        "path",
        [
            "/api/leading-stocks/candidates",
            "/api/overseas-leading-stocks/ranking",
            "/api/market/calendar/status",
            "/api/market/kospi",
            "/api/market/kosdaq",
            "/api/market/futures/night/quote",
            "/api/market/futures/night/candles",
            "/api/auth/google/callback",
        ],
    )
    def test_주도주_목록과_공개_지표는_누구나_본다(self, path):
        assert is_public(path)

    def test_끝에_빗금이_붙으면_막힌다(self):
        """지금 동작을 그대로 적어 둔다 — 의도한 것은 아닐 수 있다.

        허용목록을 `|`로 이어 붙인 뒤 `/?$`를 덧붙이는데, 교대(`|`)가 우선순위가
        가장 낮아 그 꼬리가 **마지막 항목에만** 걸린다. 그래서 `/api/auth/x/`는
        통과하고 `/api/market/kospi/`는 막힌다. 전체를 묶으면 고쳐지지만,
        공개 범위가 넓어지는 변경이라 여기서는 건드리지 않는다.
        """
        assert not is_public("/api/market/kospi/")
        assert is_public("/api/auth/google/callback/")


class Test로그인을_요구하는_경로:
    @pytest.mark.parametrize(
        "path",
        [
            # 목록은 열어도 종목 상세는 닫는다 — 국내·해외 모두
            "/api/leading-stocks/candidates/005930",
            "/api/leading-stocks/candidates/005930/minute-candles",
            "/api/overseas-leading-stocks/NAS/NVDA",
            "/api/overseas-leading-stocks/NAS/NVDA/daily-candles",
            # 야간 선물만 열었다 — 나머지 선물은 닫힌 채다
            "/api/market/futures/kospi/quote",
            "/api/market/futures/kospi/investor/sessions",
            # 가공한 판단 결과들
            "/api/leading-stocks/breakout-radar",
            "/api/leading-stocks/signal-events",
            "/api/market/nasdaq/quote",
            "/api/market/macro/quotes",
        ],
    )
    def test_상세와_가공_결과는_막힌다(self, path):
        assert not is_public(path)

    def test_허용목록에_없는_새_경로는_기본이_차단이다(self):
        assert not is_public("/api/leading-stocks/whatever-comes-next")

    def test_공개_경로를_앞에_붙여도_뚫리지_않는다(self):
        assert not is_public("/api/market/kospi/../futures/kospi/quote")
