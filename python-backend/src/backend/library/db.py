"""SQLAlchemy 엔진 / 세션.

기존 MySQL 스키마에 **그대로** 붙는다. 테이블은 Kotlin 쪽 `ddl-auto=update`가
이미 만들어 둔 것을 쓰고, Python 쪽에서 스키마를 바꾸지 않는다.
"""

from collections.abc import Iterator

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from backend.settings import get_settings


class Base(DeclarativeBase):
    pass


_engine: Engine | None = None
_session_factory: sessionmaker[Session] | None = None


def get_engine() -> Engine:
    global _engine
    if _engine is None:
        _engine = create_engine(
            get_settings().database.url,
            pool_pre_ping=True,  # 장시간 유휴 후 끊긴 커넥션을 조용히 재연결
            future=True,
        )
    return _engine


def get_session_factory() -> sessionmaker[Session]:
    global _session_factory
    if _session_factory is None:
        _session_factory = sessionmaker(bind=get_engine(), expire_on_commit=False)
    return _session_factory


def get_db() -> Iterator[Session]:
    """FastAPI 의존성. 요청 하나당 세션 하나."""
    with get_session_factory()() as session:
        yield session


def reset() -> None:
    """테스트에서 다른 DB로 갈아끼울 때 사용한다."""
    global _engine, _session_factory
    if _engine is not None:
        _engine.dispose()
    _engine = None
    _session_factory = None
