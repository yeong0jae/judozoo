"""키움 공통 인프라 — 토큰 발급·캐시·무효화, 조회 전용 리미터, 부호 파서."""

from datetime import datetime

import httpx
import pytest
import respx

from backend.platform.kiwoom import client

BASE = "https://api.kiwoom.com"
TOKEN_URL = f"{BASE}/oauth2/token"


@pytest.fixture(autouse=True)
def 키움_초기화():
    client.reset()
    yield
    client.reset()


def 토큰응답(token: str = "tok-1", return_code: int | None = 0) -> httpx.Response:
    body = {"token": token, "token_type": "Bearer", "expires_dt": "20260913000000"}
    if return_code is not None:
        body["return_code"] = return_code
    return httpx.Response(200, json=body)


class Test액세스_토큰:
    @respx.mock
    def test_발급받은_토큰을_돌려준다(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(return_value=토큰응답("tok-abc"))

        assert client.get_access_token() == "tok-abc"

    @respx.mock
    def test_두_번째_호출은_캐시를_쓴다(self, respx_mock):
        route = respx_mock.post(TOKEN_URL).mock(return_value=토큰응답())

        client.get_access_token()
        client.get_access_token()

        assert route.call_count == 1

    @respx.mock
    def test_무효화하면_다시_발급받는다(self, respx_mock):
        """다른 프로세스가 같은 앱키로 토큰을 받으면 이쪽 토큰이 즉시 죽는다."""
        route = respx_mock.post(TOKEN_URL).mock(return_value=토큰응답())

        client.get_access_token()
        client.invalidate()
        client.get_access_token()

        assert route.call_count == 2

    @respx.mock
    def test_access_token_필드로_와도_받는다(self, respx_mock):
        """응답이 token 대신 access_token으로 오는 경우가 있다."""
        respx_mock.post(TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"access_token": "tok-alt", "return_code": 0})
        )

        assert client.get_access_token() == "tok-alt"

    @respx.mock
    def test_return_code가_0이_아니면_예외(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 3, "return_msg": "인증 실패"})
        )

        with pytest.raises(RuntimeError, match="인증 실패"):
            client.get_access_token()

    @respx.mock
    def test_토큰이_비어있으면_예외(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(return_value=httpx.Response(200, json={"return_code": 0}))

        with pytest.raises(RuntimeError, match="토큰 없음"):
            client.get_access_token()

    @respx.mock
    def test_HTTP_오류는_그대로_올린다(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(return_value=httpx.Response(500))

        with pytest.raises(httpx.HTTPStatusError):
            client.get_access_token()

    @respx.mock
    def test_타임아웃은_그대로_올린다(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(side_effect=httpx.ConnectTimeout("timeout"))

        with pytest.raises(httpx.ConnectTimeout):
            client.get_access_token()


class Test조회_리미터:
    def test_조회_TR만_허가를_받는다(self, monkeypatch):
        """ka*는 초당 5건 한도를 타고, 주문·계좌(kt*)는 별도 한도라 통과시킨다."""
        받은_요청 = []

        class 가짜리미터:
            def acquire(self):
                받은_요청.append("허가")

        class 가짜전송:
            def handle_request(self, request):
                return httpx.Response(200)

        transport = client.QueryRateLimitedTransport(가짜리미터(), 가짜전송())

        transport.handle_request(
            httpx.Request("POST", BASE, headers={client.API_ID_HEADER: "ka10032"})
        )
        assert len(받은_요청) == 1

        transport.handle_request(
            httpx.Request("POST", BASE, headers={client.API_ID_HEADER: "kt10000"})
        )
        assert len(받은_요청) == 1  # 늘지 않는다

        transport.handle_request(httpx.Request("POST", BASE))
        assert len(받은_요청) == 1  # 헤더 없으면 통과


class Test피크타임_한도:
    """09:00~10:00에는 키움 조회 한도가 초당 5건에서 3건으로 내려간다."""

    @pytest.mark.parametrize(
        "시각, 초당",
        [
            (datetime(2026, 9, 28, 8, 59, 59), 5),
            (datetime(2026, 9, 28, 9, 0), 3),
            (datetime(2026, 9, 28, 9, 59, 59), 3),
            (datetime(2026, 9, 28, 10, 0), 5),
        ],
    )
    def test_시간대에_맞는_간격으로_허가를_낸다(self, monkeypatch, 시각, 초당):
        monkeypatch.setattr(client, "now", lambda: 시각)

        assert client._query_period() == pytest.approx(1 / 초당)


class Test부호_파서:
    @pytest.mark.parametrize(
        "입력,기대",
        [("+1.23", 1.23), ("-1.23", -1.23), ("1.23-", -1.23), ("1.23", 1.23), ("", 0.0), (None, 0.0), ("abc", 0.0)],
    )
    def test_실수_부호_변종을_흡수한다(self, 입력, 기대):
        """후위 부호("1.23-")가 실제로 온다 — 그냥 float()에 넣으면 0으로 떨어진다."""
        assert client.parse_signed_float(입력) == 기대

    @pytest.mark.parametrize(
        "입력,기대",
        [("+255", 255), ("-622", -622), ("1234-", -1234), ("0", 0), ("", 0), (None, 0)],
    )
    def test_정수_부호_변종을_흡수한다(self, 입력, 기대):
        assert client.parse_signed_int(입력) == 기대


class Test토큰_발급_백오프:
    """발급이 거부되면 일정 시간 다시 두드리지 않는다.

    백오프가 없으면 실패할 때마다 매 호출이 토큰을 재요청한다 —
    운영에서 11분에 185회를 두드린 적이 있다. 차단이라면 풀릴 기회도 없어진다.
    """

    @respx.mock
    def test_거부되면_다음_호출은_키움을_두드리지_않는다(self, respx_mock):
        route = respx_mock.post(TOKEN_URL).mock(return_value=httpx.Response(302))

        with pytest.raises(httpx.HTTPStatusError):
            client.get_access_token()
        with pytest.raises(client.KiwoomTokenUnavailable, match="백오프"):
            client.get_access_token()

        assert route.call_count == 1  # 두 번째는 네트워크로 안 나간다

    @respx.mock
    def test_응답에_토큰이_없어도_백오프한다(self, respx_mock):
        route = respx_mock.post(TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0})
        )

        with pytest.raises(RuntimeError):
            client.get_access_token()
        with pytest.raises(client.KiwoomTokenUnavailable):
            client.get_access_token()

        assert route.call_count == 1

    @respx.mock
    def test_오류_코드로_거부되면_백오프한다(self, respx_mock):
        respx_mock.post(TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 3, "return_msg": "거부"})
        )

        with pytest.raises(RuntimeError, match="거부"):
            client.get_access_token()
        with pytest.raises(client.KiwoomTokenUnavailable):
            client.get_access_token()

    @respx.mock
    def test_백오프가_지나면_다시_시도한다(self, respx_mock, monkeypatch):
        route = respx_mock.post(TOKEN_URL).mock(
            side_effect=[httpx.Response(302), 토큰응답("tok-recovered")]
        )

        with pytest.raises(httpx.HTTPStatusError):
            client.get_access_token()

        # 백오프 만료를 과거로 밀어 시간 경과를 흉내 낸다
        from datetime import UTC, datetime
        monkeypatch.setattr(client, "_token_retry_after", datetime.min.replace(tzinfo=UTC))

        assert client.get_access_token() == "tok-recovered"
        assert route.call_count == 2

    @respx.mock
    def test_8005_무효화는_백오프를_걸지_않는다(self, respx_mock):
        """외부에서 토큰이 끊긴 상황은 즉시 재발급이 맞다 — 발급 거부와 다르다."""
        route = respx_mock.post(TOKEN_URL).mock(return_value=토큰응답())

        client.get_access_token()
        client.invalidate()
        client.get_access_token()

        assert route.call_count == 2
