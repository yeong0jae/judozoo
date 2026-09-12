"""토스 — 토큰 401 재시도, 장 운영 판정, 투자자 매매대금·캔들."""

from datetime import date

import httpx
import pytest
import respx

from backend.platform.toss import client, market_calendar, market_indicator as mi

BASE = "https://openapi.tossinvest.com"
TOKEN_URL = f"{BASE}/oauth2/token"
KR_CALENDAR_URL = f"{BASE}/api/v1/market-calendar/KR"
US_CALENDAR_URL = f"{BASE}/api/v1/market-calendar/US"
INVESTOR_URL = f"{BASE}/api/v1/market-indicators/KOSPI/investor-trading"
CANDLES_URL = f"{BASE}/api/v1/market-indicators/KOSPI/candles"


@pytest.fixture(autouse=True)
def 토스_초기화():
    client.reset()
    yield
    client.reset()


@pytest.fixture
def 토큰_발급(respx_mock):
    respx_mock.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"access_token": "tok", "expires_in": 86400})
    )


class Test액세스_토큰:
    @respx.mock
    def test_발급받아_캐시한다(self, respx_mock, 토큰_발급):
        route = respx_mock.routes[0]

        client.get_access_token()
        client.get_access_token()

        assert route.call_count == 1

    @respx.mock
    def test_수명이_짧아도_최소치는_보장한다(self, respx_mock):
        """expires_in이 만료 마진보다 짧으면 음수 수명이 된다."""
        respx_mock.post(TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"access_token": "tok", "expires_in": 60})
        )

        assert client.get_access_token() == "tok"

    @respx.mock
    def test_토큰이_비면_예외(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(return_value=httpx.Response(200, json={}))

        with pytest.raises(RuntimeError, match="비어있음"):
            client.get_access_token()

    @respx.mock
    def test_401이면_캐시를_버리고_한_번_재시도한다(self, respx_mock, 토큰_발급):
        """client당 토큰이 1개라 다른 프로세스가 발급받으면 이쪽이 즉시 죽는다."""
        respx_mock.get(KR_CALENDAR_URL).mock(side_effect=[
            httpx.Response(401),
            httpx.Response(200, json={"result": {"today": {"integrated": {"regularMarket": {"startTime": "09:00"}}}}}),
        ])

        assert market_calendar.is_trading_day("KR", date(2026, 9, 11)) is True


class Test장_운영_판정:
    @respx.mock
    def test_국내는_통합_세션이_있으면_개장(self, respx_mock, 토큰_발급):
        respx_mock.get(KR_CALENDAR_URL).mock(
            return_value=httpx.Response(200, json={
                "result": {"today": {"integrated": {"regularMarket": {"startTime": "09:00"}}}}
            })
        )

        assert market_calendar.is_trading_day("KR", date(2026, 9, 11)) is True

    @respx.mock
    def test_국내_세션이_전부_비면_휴장(self, respx_mock, 토큰_발급):
        respx_mock.get(KR_CALENDAR_URL).mock(
            return_value=httpx.Response(200, json={
                "result": {"today": {"integrated": {"regularMarket": None, "preMarket": None}}}
            })
        )

        assert market_calendar.is_trading_day("KR", date(2026, 9, 12)) is False

    @respx.mock
    def test_해외는_네_세션_중_하나라도_있으면_개장(self, respx_mock, 토큰_발급):
        respx_mock.get(US_CALENDAR_URL).mock(
            return_value=httpx.Response(200, json={
                "result": {"today": {"preMarket": {"startTime": "17:00"}, "regularMarket": None}}
            })
        )

        assert market_calendar.is_trading_day("US", date(2026, 9, 11)) is True

    @respx.mock
    def test_해외_네_세션이_전부_비면_휴장(self, respx_mock, 토큰_발급):
        respx_mock.get(US_CALENDAR_URL).mock(
            return_value=httpx.Response(200, json={
                "result": {"today": {"dayMarket": None, "preMarket": None,
                                     "regularMarket": None, "afterMarket": None}}
            })
        )

        assert market_calendar.is_trading_day("US", date(2026, 9, 12)) is False

    @respx.mock
    def test_조회_실패면_None(self, respx_mock, 토큰_발급):
        """휴장으로 단정하면 안 된다 — 호출측이 폴백하도록 모름을 알린다."""
        respx_mock.get(KR_CALENDAR_URL).mock(return_value=httpx.Response(500))

        assert market_calendar.is_trading_day("KR", date(2026, 9, 11)) is None

    @respx.mock
    def test_타임아웃이면_None(self, respx_mock, 토큰_발급):
        respx_mock.get(KR_CALENDAR_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        assert market_calendar.is_trading_day("KR", date(2026, 9, 11)) is None


class Test순매수_환산:
    @pytest.mark.parametrize(
        "매수,매도,기대",
        [
            ("300000000", "100000000", 2),       # +2억
            ("100000000", "300000000", -2),      # -2억
            ("250000000", "0", 3),               # 2.5억 → Kotlin roundToLong은 올림
            ("0", "250000000", -2),              # -2.5억 → +무한대 쪽으로
            (None, None, 0),
        ],
    )
    def test_매수에서_매도를_빼_억원으로_반올림한다(self, 매수, 매도, 기대):
        """Kotlin `roundToLong()`은 floor(x+0.5)다 — Python 기본 round()의 은행가 반올림과 갈린다."""
        assert mi.net_eok({"buyAmount": 매수, "sellAmount": 매도}) == 기대

    def test_없으면_0(self):
        assert mi.net_eok(None) == 0


class Test투자자_매매대금:
    def 레코드(self) -> dict:
        return {
            "date": "2026-09-11", "updatedAt": "2026-09-11T18:10:00+09:00",
            "individual": {"buyAmount": "300000000", "sellAmount": "100000000"},
            "foreigner": {"buyAmount": "100000000", "sellAmount": "400000000"},
            "institution": {
                "buyAmount": "500000000", "sellAmount": "400000000",
                "breakdown": {
                    "pensionFund": {"buyAmount": "200000000", "sellAmount": "100000000"},
                    "trust": {"buyAmount": "0", "sellAmount": "100000000"},
                },
            },
            "otherCorporation": {"buyAmount": "0", "sellAmount": "0"},
        }

    @respx.mock
    def test_투자자별_순매수를_억원으로_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"result": {"records": [self.레코드()]}})
        )

        r = mi.fetch_investor_trading("KOSPI")[0]

        assert r.date == date(2026, 9, 11)
        assert (r.individual_net_eok, r.foreign_net_eok, r.institution_net_eok) == (2, -3, 1)

    @respx.mock
    def test_기관_합계는_세부_합이_아니라_자신의_매수매도로_낸다(self, respx_mock, 토큰_발급):
        respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"result": {"records": [self.레코드()]}})
        )

        r = mi.fetch_investor_trading("KOSPI")[0]

        assert r.institution_net_eok == 1                  # 5억 − 4억
        assert r.breakdown.pension_fund_eok == 1
        assert r.breakdown.trust_eok == -1

    @respx.mock
    def test_날짜가_없는_레코드는_버린다(self, respx_mock, 토큰_발급):
        respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"result": {"records": [{"date": None}, self.레코드()]}})
        )

        assert len(mi.fetch_investor_trading("KOSPI")) == 1

    @respx.mock
    def test_기준일을_주면_요청에_싣는다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"result": {"records": []}})
        )

        mi.fetch_investor_trading("KOSPI", until=date(2026, 9, 11))

        assert "until=2026-09-11" in str(route.calls[0].request.url)

    @respx.mock
    def test_실패하면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.get(INVESTOR_URL).mock(return_value=httpx.Response(500))

        assert mi.fetch_investor_trading("KOSPI") == []


class Test캔들:
    @respx.mock
    def test_OHLCV와_다음_커서를_준다(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(
            return_value=httpx.Response(200, json={"result": {
                "candles": [{
                    "timestamp": "2026-09-11T09:00:00+09:00", "openPrice": "2600.0",
                    "highPrice": "2650.0", "lowPrice": "2590.0", "closePrice": "2640.0",
                    "volume": "1200",
                }],
                "nextBefore": "cursor-1",
            }})
        )

        page = mi.fetch_candles("KOSPI", "1d", 10)

        assert page.next_before == "cursor-1"
        c = page.candles[0]
        assert (c.open, c.high, c.low, c.close, c.volume) == (2600.0, 2650.0, 2590.0, 2640.0, 1200.0)
        assert c.timestamp.hour == 9

    @respx.mock
    def test_OHLC가_하나라도_없으면_캔들이_아니다(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(
            return_value=httpx.Response(200, json={"result": {"candles": [{
                "timestamp": "2026-09-11T09:00:00+09:00", "openPrice": "2600.0",
                "highPrice": None, "lowPrice": "2590.0", "closePrice": "2640.0",
            }]}})
        )

        assert mi.fetch_candles("KOSPI", "1d", 10).candles == []

    @respx.mock
    def test_실패하면_빈_페이지(self, respx_mock, 토큰_발급):
        respx_mock.get(CANDLES_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        page = mi.fetch_candles("KOSPI", "1m", 10)

        assert (page.candles, page.next_before) == ([], None)
