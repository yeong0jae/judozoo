"""브로커 토큰 영속화 — 재기동이 발급을 소비하지 않게 한다.

2026-09-12에 이것이 없어서 국내 화면 전체가 죽었다. 토요일 오후에 재기동했고
그 시각엔 키움이 신규 발급을 거부해 되살릴 방법이 없었다.
"""

from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx

from backend.library import db, token_store
from backend.library.db import get_engine
from backend.platform.kiwoom import client as kiwoom
from backend.platform.toss import client as toss

KIWOOM_TOKEN_URL = "https://api.kiwoom.com/oauth2/token"
TOSS_TOKEN_URL = "https://openapi.tossinvest.com/oauth2/token"


@pytest.fixture
def 빈_토큰_테이블(통합_db):
    token_store.create_table()
    with db.get_session_factory()() as s:
        s.query(token_store.BrokerToken).delete()
        s.commit()
    kiwoom.reset()
    toss.reset()
    yield
    kiwoom.reset()
    toss.reset()


def 키움_토큰응답(token="tok-new"):
    return httpx.Response(200, json={"token": token, "return_code": 0})


class Test저장소:
    def test_저장한_토큰을_그대로_읽는다(self, 빈_토큰_테이블):
        만료 = datetime.now(UTC) + timedelta(hours=20)

        token_store.save("KIWOOM", "tok-abc", 만료)
        복원 = token_store.load("KIWOOM")

        assert 복원 is not None
        assert 복원[0] == "tok-abc"
        # 초 단위 반올림만 감안하면 같은 시각
        assert abs((복원[1] - 만료).total_seconds()) < 1

    def test_JWT처럼_긴_토큰도_잘리지_않는다(self, 빈_토큰_테이블):
        """토스 토큰은 JWT라 750자를 넘는다 — varchar(512)로 뒀다가 저장이 통째로 실패했다."""
        긴토큰 = "eyJ" + "A" * 900 + ".sig"

        token_store.save("TOSS", 긴토큰, datetime.now(UTC) + timedelta(hours=20))

        assert token_store.load("TOSS")[0] == 긴토큰

    def test_만료된_토큰은_없는_것으로_본다(self, 빈_토큰_테이블):
        token_store.save("KIWOOM", "tok-old", datetime.now(UTC) - timedelta(minutes=1))

        assert token_store.load("KIWOOM") is None

    def test_같은_브로커는_덮어쓴다(self, 빈_토큰_테이블):
        만료 = datetime.now(UTC) + timedelta(hours=20)
        token_store.save("KIWOOM", "tok-1", 만료)

        token_store.save("KIWOOM", "tok-2", 만료)

        assert token_store.load("KIWOOM")[0] == "tok-2"
        with db.get_session_factory()() as s:
            assert s.query(token_store.BrokerToken).count() == 1

    def test_브로커끼리_섞이지_않는다(self, 빈_토큰_테이블):
        만료 = datetime.now(UTC) + timedelta(hours=20)
        token_store.save("KIWOOM", "tok-kiwoom", 만료)
        token_store.save("KIS", "tok-kis", 만료)

        assert token_store.load("KIWOOM")[0] == "tok-kiwoom"
        assert token_store.load("KIS")[0] == "tok-kis"

    def test_지우면_없어진다(self, 빈_토큰_테이블):
        token_store.save("TOSS", "tok-x", datetime.now(UTC) + timedelta(hours=20))

        token_store.clear("TOSS")

        assert token_store.load("TOSS") is None

    def test_없는_브로커를_지워도_터지지_않는다(self, 빈_토큰_테이블):
        token_store.clear("KIWOOM")  # 예외 없이 통과해야 한다

    def test_테이블_생성은_멱등이다(self, 빈_토큰_테이블):
        token_store.create_table()
        token_store.create_table()


class TestDB가_없을_때:
    """DB 문제로 인증이 멈추면 안 된다 — 경고만 남기고 메모리 전용으로 동작한다."""

    def test_읽기는_None을_준다(self, monkeypatch):
        monkeypatch.setattr(db, "get_session_factory", lambda: (_ for _ in ()).throw(RuntimeError("DB 없음")))

        assert token_store.load("KIWOOM") is None

    def test_쓰기는_예외를_밖으로_내지_않는다(self, monkeypatch):
        monkeypatch.setattr(db, "get_session_factory", lambda: (_ for _ in ()).throw(RuntimeError("DB 없음")))

        token_store.save("KIWOOM", "tok", datetime.now(UTC) + timedelta(hours=1))

    def test_삭제도_예외를_내지_않는다(self, monkeypatch):
        monkeypatch.setattr(db, "get_session_factory", lambda: (_ for _ in ()).throw(RuntimeError("DB 없음")))

        token_store.clear("KIWOOM")


class Test키움_재기동:
    @respx.mock
    def test_저장된_토큰이_있으면_발급을_요청하지_않는다(self, 빈_토큰_테이블, respx_mock):
        """이게 이 기능의 존재 이유다 — 재기동이 발급 한도를 건드리지 않는다."""
        route = respx_mock.post(KIWOOM_TOKEN_URL).mock(return_value=키움_토큰응답())
        token_store.save("KIWOOM", "tok-persisted", datetime.now(UTC) + timedelta(hours=20))

        assert kiwoom.get_access_token() == "tok-persisted"
        assert route.call_count == 0  # 네트워크로 안 나간다

    @respx.mock
    def test_발급받으면_저장한다(self, 빈_토큰_테이블, respx_mock):
        respx_mock.post(KIWOOM_TOKEN_URL).mock(return_value=키움_토큰응답("tok-fresh"))

        kiwoom.get_access_token()

        assert token_store.load("KIWOOM")[0] == "tok-fresh"

    @respx.mock
    def test_재기동을_흉내_내도_발급은_한_번뿐이다(self, 빈_토큰_테이블, respx_mock):
        route = respx_mock.post(KIWOOM_TOKEN_URL).mock(return_value=키움_토큰응답("tok-once"))

        kiwoom.get_access_token()
        for _ in range(3):
            kiwoom.reset()  # 컨테이너 재기동 = 메모리 캐시 소실
            assert kiwoom.get_access_token() == "tok-once"

        assert route.call_count == 1

    @respx.mock
    def test_무효화하면_저장된_것도_지워_다시_발급한다(self, 빈_토큰_테이블, respx_mock):
        """8005는 토큰이 외부에서 죽은 것 — DB에 남겨두면 재기동 시 죽은 토큰을 되살린다."""
        route = respx_mock.post(KIWOOM_TOKEN_URL).mock(
            side_effect=[키움_토큰응답("tok-1"), 키움_토큰응답("tok-2")]
        )
        kiwoom.get_access_token()

        kiwoom.invalidate()
        assert token_store.load("KIWOOM") is None

        assert kiwoom.get_access_token() == "tok-2"
        assert route.call_count == 2

    @respx.mock
    def test_저장된_토큰이_만료됐으면_새로_발급한다(self, 빈_토큰_테이블, respx_mock):
        respx_mock.post(KIWOOM_TOKEN_URL).mock(return_value=키움_토큰응답("tok-new"))
        token_store.save("KIWOOM", "tok-expired", datetime.now(UTC) - timedelta(minutes=1))

        assert kiwoom.get_access_token() == "tok-new"

    @respx.mock
    def test_저장된_토큰이_있으면_백오프도_건너뛴다(self, 빈_토큰_테이블, respx_mock):
        """발급이 막혀 백오프 중이어도, 살아 있는 토큰이 있으면 서비스는 계속돼야 한다."""
        respx_mock.post(KIWOOM_TOKEN_URL).mock(return_value=httpx.Response(302))
        with pytest.raises(httpx.HTTPStatusError):
            kiwoom.get_access_token()

        token_store.save("KIWOOM", "tok-rescued", datetime.now(UTC) + timedelta(hours=20))
        kiwoom.reset()

        assert kiwoom.get_access_token() == "tok-rescued"


class Test토스_재기동:
    @respx.mock
    def test_저장된_토큰을_재사용한다(self, 빈_토큰_테이블, respx_mock):
        """토스는 client당 유효 토큰이 1개 — 재발급하면 직전 토큰이 죽는다."""
        route = respx_mock.post(TOSS_TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"access_token": "tok-new", "expires_in": 86400})
        )
        token_store.save("TOSS", "tok-persisted", datetime.now(UTC) + timedelta(hours=20))

        assert toss.get_access_token() == "tok-persisted"
        assert route.call_count == 0

    @respx.mock
    def test_401_무효화는_저장된_것도_지운다(self, 빈_토큰_테이블, respx_mock):
        respx_mock.post(TOSS_TOKEN_URL).mock(
            return_value=httpx.Response(200, json={"access_token": "tok-1", "expires_in": 86400})
        )
        toss.get_access_token()

        toss.invalidate()

        assert token_store.load("TOSS") is None
