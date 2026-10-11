"""시간 — 시스템 전반의 단일 시간대(KST).

모든 naive datetime을 KST로 해석한다. 테스트는 이 함수를 monkeypatch하거나 시각을 파라미터로
받는 쪽을 쓴다(도메인은 시간을 주입받는다).

DB에 넣는 값은 **naive**로 맞춘다 — 컬럼이 DATETIME이라 tzinfo를 붙이면 드라이버가 거부한다."""

from datetime import date, datetime
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")


def now() -> datetime:
    """KST 벽시계. tzinfo 없는 naive — DB DATETIME 컬럼과 같은 모양."""
    return datetime.now(KST).replace(tzinfo=None)


def today() -> date:
    return now().date()
