import os

import pytest
from fastapi.testclient import TestClient

# Ryuk(정리 담당 컨테이너)은 docker 소켓을 bind mount 하는데, Docker Desktop이
# 소켓을 ~/.docker/run/ 아래에 두면 마운트가 거부된다("operation not supported").
# 컨테이너는 컨텍스트 매니저가 정리하므로 Ryuk 없이도 남지 않는다.
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

# 테스트에서는 폴러·캡처를 띄우지 않는다 (Kotlin @Profile("!test")에 대응).
os.environ.setdefault("SCHEDULERS_ENABLED", "false")

from backend.library import cache, db
from backend.main import app
from backend.settings import get_settings


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def 담긴_값(user_id: int | None) -> dict:
    raw = {"sub": "test-sub", "email": "tester@example.com"}
    if user_id is not None:
        raw["id"] = user_id
    return raw


def 세션_쿠키(user_id: int | None = 7) -> str:
    """서명된 세션 쿠키 값. 구글을 실제로 다녀오지 않고 관문만 통과시킨다.

    `user_id=None`이면 대체 키(V006) 이전에 발급된 **옛 세션**이 된다.
    """
    import base64
    import json

    from itsdangerous import TimestampSigner

    from backend.auth.domain import SESSION_KEY

    payload = base64.b64encode(
        json.dumps({SESSION_KEY: 담긴_값(user_id)}).encode()
    )
    return TimestampSigner(get_settings().session_secret).sign(payload).decode()


@pytest.fixture
def 로그인_client(client) -> TestClient:
    """관문 뒤 동작을 검증할 때 쓴다."""
    client.cookies.set("judozoo_session", 세션_쿠키())
    return client


@pytest.fixture(autouse=True)
def 캐시_격리():
    """테스트끼리 캐시를 공유하지 않는다."""
    cache.clear_all()
    yield
    cache.clear_all()


@pytest.fixture(autouse=True)
def 지난_날_분봉_DB_격리(monkeypatch):
    """지난 날 분봉 보관소가 로컬 MySQL(docker compose)에 실제로 쓰지 않게 한다.

    DB 층까지 보는 테스트는 `통합_db`와 함께 `minute_archive.get_session_factory`를 되돌린다.
    """
    from backend.leadingstock import minute_archive
    from backend.market import minute_archive as index_minute_archive
    from backend.overseasleadingstock import minute_archive as overseas_minute_archive

    def 없는_DB():
        raise RuntimeError("테스트에서는 DB를 쓰지 않는다")

    monkeypatch.setattr(minute_archive, "get_session_factory", 없는_DB)
    monkeypatch.setattr(index_minute_archive, "get_session_factory", 없는_DB)
    monkeypatch.setattr(overseas_minute_archive, "get_session_factory", 없는_DB)
    overseas_minute_archive.reset()
    from backend.leadingstock import wide_limit_days

    monkeypatch.setattr(wide_limit_days, "get_session_factory", 없는_DB)
    wide_limit_days.reset()


@pytest.fixture(scope="session")
def mysql_container():
    """Kotlin의 `IntegrationTestBase`에 대응한다. Docker가 필요하다."""
    from testcontainers.community.mysql import MySqlContainer

    with MySqlContainer("mysql:8.4") as container:
        yield container


@pytest.fixture
def 통합_db(mysql_container, monkeypatch):
    """컨테이너 MySQL로 엔진을 갈아끼운다."""
    monkeypatch.setenv("DB_HOST", mysql_container.get_container_host_ip())
    monkeypatch.setenv("DB_PORT", str(mysql_container.get_exposed_port(3306)))
    monkeypatch.setenv("DB_NAME", mysql_container.dbname)
    monkeypatch.setenv("DB_USERNAME", mysql_container.username)
    monkeypatch.setenv("DB_PASSWORD", mysql_container.password)

    get_settings.cache_clear()
    db.reset()
    yield
    get_settings.cache_clear()
    db.reset()
