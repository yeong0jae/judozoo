"""Alembic 실행 환경. DB 주소는 앱과 같은 settings에서 읽는다."""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine, pool

# 모델을 모두 불러와야 Base.metadata가 채워진다 — 빠지면 autogenerate가 그 테이블을 모른다.
import backend.auth.domain  # noqa: F401
import backend.closingbet.entities  # noqa: F401
import backend.feedback.domain  # noqa: F401
import backend.insight.entities  # noqa: F401
import backend.leadercalendar.entities  # noqa: F401
import backend.leadertimeline.entities  # noqa: F401
import backend.leadingstock.entities  # noqa: F401
import backend.leadingstock.infrastructure  # noqa: F401
import backend.library.token_store  # noqa: F401
import backend.market.domain  # noqa: F401
import backend.overseasleadingstock.infrastructure  # noqa: F401
import backend.stock.domain  # noqa: F401
from backend.library.db import Base
from backend.settings import get_settings

config = context.config
# 테스트는 끈다. fileConfig가 기존 로거를 꺼 버려서 뒤따르는 테스트의 로그 검증이 깨진다.
if config.config_file_name is not None and config.attributes.get("configure_logger", True):
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def include_object(obj, name, type_, reflected, compare_to) -> bool:
    """모델에 없는 DB 테이블은 비교하지 않는다.

    운영 DB에는 Kotlin 시절 테이블이 남아 있을 수 있다. 비교에 넣으면 autogenerate가
    `drop_table`을 만들어 버린다. 테이블을 지울 때는 마이그레이션에 손으로 쓴다.
    """
    return not (type_ == "table" and reflected and compare_to is None)


def run_migrations_offline() -> None:
    context.configure(
        url=get_settings().database.url,
        target_metadata=target_metadata,
        include_object=include_object,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = create_engine(get_settings().database.url, poolclass=pool.NullPool)
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata, include_object=include_object)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
