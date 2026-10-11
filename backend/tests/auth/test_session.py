"""세션 복원과 로그인 관문."""

from backend.auth.domain import SESSION_KEY, CurrentUser


class Test세션_복원:
    def test_담아둔_값에서_사용자를_되살린다(self):
        원본 = CurrentUser(id=7, email="a@b.com")

        assert CurrentUser.from_session(원본.to_session()) == 원본

    def test_세션이_비어_있으면_사용자가_없다(self):
        assert CurrentUser.from_session(None) is None

    def test_모양이_다른_값은_사용자로_치지_않는다(self):
        assert CurrentUser.from_session("문자열") is None
        assert CurrentUser.from_session({"id": 7}) is None
        assert CurrentUser.from_session({"email": "a@b.com"}) is None

    def test_식별자가_숫자가_아니면_사용자로_치지_않는다(self):
        assert CurrentUser.from_session({"id": "일곱", "email": "a@b.com"}) is None
        assert CurrentUser.from_session({"id": True, "email": "a@b.com"}) is None


class Test로그인_상태_조회:
    def test_미로그인이면_인증되지_않았다고_답한다(self, client):
        res = client.get("/api/auth/me")

        assert res.status_code == 200
        assert res.json()["data"] == {"authenticated": False, "email": None}

    def test_로그인하면_이메일을_돌려준다(self, 로그인_client):
        res = 로그인_client.get("/api/auth/me")

        assert res.json()["data"]["authenticated"] is True


class Test실시간_로그_관문:
    def test_미로그인이면_시장_시그널을_막는다(self, client):
        """종목 시그널은 미리보기로 열렸지만 지수 시그널은 로그인 뒤다."""
        res = client.get("/api/leading-stocks/market-signal-events")

        assert res.status_code == 401
        assert res.json()["code"] == "UNAUTHORIZED"

    def test_공개_화면은_로그인_없이도_열린다(self, client):
        assert client.get("/health").status_code == 200


class Test여정_추적:
    """누가 무엇을 보고 갔는지 로그로 잇기 위해, 관문이 사용자를 로그 컨텍스트에 심는다."""

    def test_로그인한_요청은_사용자를_심는다(self, 로그인_client, mocker):
        심기 = mocker.patch("backend.main.bind_user")

        로그인_client.get("/api/auth/me")

        심기.assert_called_with(7)

    def test_미로그인_요청은_비워_심는다(self, client, mocker):
        """비워 심지 않으면 앞 요청의 사용자가 남아 남의 여정에 섞인다."""
        심기 = mocker.patch("backend.main.bind_user")

        client.get("/api/auth/me")

        심기.assert_called_with(None)


def 쿠키에_담긴_사용자(set_cookie: str) -> dict:
    """응답이 내려보낸 세션 쿠키를 풀어 본다. 서명은 SessionMiddleware가 검증하므로 여기선 내용만 본다."""
    import base64
    import json

    value = set_cookie.split(";", 1)[0].split("=", 1)[1]
    payload = value.rsplit(".", 2)[0]  # itsdangerous: 내용.시각.서명
    return json.loads(base64.b64decode(payload))[SESSION_KEY]


class Test구글_계정_식별자를_쿠키에_싣지_않는다:
    """쿠키는 서명만 되고 암호화되지 않는다. 모든 앱에 같은 구글 `sub`이 요청마다 실려 다니면 안 된다."""

    def test_세션에_담는_값에_구글_식별자가_없다(self):
        assert CurrentUser(id=7, email="a@b.com").to_session() == {"id": 7, "email": "a@b.com"}

    def test_옛_쿠키는_다음_요청에서_구글_식별자를_지운다(self, client):
        from tests.conftest import 세션_쿠키

        client.cookies.set("judozoo_session", 세션_쿠키(sub="test-sub"))

        res = client.get("/api/auth/me")

        assert res.json()["data"]["authenticated"] is True
        assert 쿠키에_담긴_사용자(res.headers["set-cookie"]) == {"id": 7, "email": "tester@example.com"}

    def test_지금_모양의_쿠키는_다시_내려보내지_않는다(self, 로그인_client):
        """다시 쓸 이유가 없는 요청마다 쿠키를 내려보내면 응답이 쓸데없이 커진다."""
        res = 로그인_client.get("/api/auth/me")

        assert "set-cookie" not in res.headers

    def test_내부_식별자가_없는_옛_세션은_로그인이_풀린다(self, client):
        """대체 키(V006) 이전 쿠키다. 14일 수명이 지나 이제 남아 있지 않다고 보고, 다시 로그인하게 한다."""
        import base64
        import json

        from itsdangerous import TimestampSigner

        from backend.settings import get_settings

        payload = base64.b64encode(json.dumps({SESSION_KEY: {"sub": "test-sub", "email": "a@b.com"}}).encode())
        client.cookies.set("judozoo_session", TimestampSigner(get_settings().session_secret).sign(payload).decode())

        assert client.get("/api/auth/me").json()["data"]["authenticated"] is False
