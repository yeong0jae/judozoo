"""스키마 마이그레이션 — 처음부터 끝까지 올라가는가, 모델과 어긋나지 않는가."""

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import inspect, text

from backend.library.db import get_engine


def 설정() -> Config:
    cfg = Config(str(Path(__file__).parents[2] / "alembic.ini"))
    cfg.attributes["configure_logger"] = False
    return cfg


def 적용된_버전() -> str | None:
    with get_engine().connect() as conn:
        return conn.execute(text("SELECT version_num FROM alembic_version")).scalar()


def 최신_버전() -> str:
    return ScriptDirectory.from_config(설정()).get_current_head()


@pytest.fixture
def 빈_DB(통합_db):
    """컨테이너는 테스트 세션 전체가 같이 쓴다. 다른 테스트가 만든 테이블을 걷어 낸다."""
    engine = get_engine()
    with engine.begin() as conn:
        for name in inspect(conn).get_table_names():
            conn.execute(text(f"DROP TABLE `{name}`"))
    yield


@pytest.mark.integration
class Test스키마_마이그레이션:
    def test_빈_DB에_최신까지_올리면_모델과_어긋나지_않는다(self, 빈_DB):
        command.upgrade(설정(), "head")

        assert 적용된_버전() == 최신_버전()
        command.check(설정())  # 모델만 고치고 마이그레이션을 빠뜨렸으면 여기서 실패한다

    def test_테이블이_이미_있는_DB도_기록만_남기고_올라간다(self, 빈_DB):
        """Alembic 도입 전 운영 DB의 모양이다 — 테이블은 다 있고 이력 테이블만 없다."""
        command.upgrade(설정(), "head")
        with get_engine().begin() as conn:
            conn.execute(text("DROP TABLE alembic_version"))

        command.upgrade(설정(), "head")

        assert 적용된_버전() == 최신_버전()
