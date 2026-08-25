import pytest
from sqlalchemy import text

from backend.library.db import get_engine


class Test헬스체크:
    def test_기동_확인_요청에_UP을_응답한다(self, client):
        response = client.get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "UP"}


@pytest.mark.integration
class TestDB_연결:
    def test_컨테이너_MySQL에_붙어_질의할_수_있다(self, 통합_db):
        with get_engine().connect() as conn:
            assert conn.execute(text("SELECT 1")).scalar() == 1

    def test_DB_헬스체크가_UP을_응답한다(self, 통합_db, client):
        response = client.get("/health/db")

        assert response.status_code == 200
        assert response.json()["database"] == "UP"
