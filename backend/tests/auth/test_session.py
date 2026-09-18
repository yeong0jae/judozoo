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


class Test내부_식별자:
    """로그에 담는 것은 구글 계정 식별자가 아니라 이 시스템이 발급한 id다."""

    def test_세션을_오가며_식별자가_보존된다(self):
        원본 = CurrentUser(google_sub="1234", email="a@b.com", id=7)

        assert CurrentUser.from_session(원본.to_session()) == 원본

    def test_식별자가_없는_옛_세션도_사용자로_친다(self):
        """로그인은 계속 되게 두고, 로그에만 사용자가 안 실린다. 다시 로그인하면 채워진다."""
        복원 = CurrentUser.from_session({"sub": "1234", "email": "a@b.com"})

        assert 복원 is not None
        assert 복원.id is None

    def test_식별자가_숫자가_아니면_없는_것으로_친다(self):
        복원 = CurrentUser.from_session({"sub": "1234", "email": "a@b.com", "id": "일곱"})

        assert 복원 is not None and 복원.id is None
