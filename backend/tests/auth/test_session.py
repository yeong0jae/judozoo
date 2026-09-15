"""세션 복원과 로그인 관문."""

from backend.auth.domain import SESSION_KEY, CurrentUser


class Test세션_복원:
    def test_담아둔_값에서_사용자를_되살린다(self):
        원본 = CurrentUser(google_sub="1234", email="a@b.com")

        복원 = CurrentUser.from_session(원본.to_session())

        assert 복원 == 원본

    def test_세션이_비어_있으면_사용자가_없다(self):
        assert CurrentUser.from_session(None) is None

    def test_모양이_다른_값은_사용자로_치지_않는다(self):
        assert CurrentUser.from_session("문자열") is None
        assert CurrentUser.from_session({"sub": "1234"}) is None
        assert CurrentUser.from_session({"email": "a@b.com"}) is None

    def test_식별자가_비면_사용자로_치지_않는다(self):
        assert CurrentUser.from_session({"sub": "", "email": "a@b.com"}) is None


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
