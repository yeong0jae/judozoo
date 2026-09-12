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


@pytest.fixture(autouse=True)
def 캐시_격리():
    """테스트끼리 캐시를 공유하지 않는다."""
    cache.clear_all()
    yield
    cache.clear_all()


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
