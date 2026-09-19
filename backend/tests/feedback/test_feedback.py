"""의견 보내기 — 관문·계약, 저장되는 모양, 그리고 알림이 읽어 가는 질의."""

from datetime import datetime
from pathlib import Path

import pytest
import yaml
from sqlalchemy import text

from backend.auth.domain import CurrentUser
from backend.feedback import application
from backend.feedback.domain import Feedback
from backend.library import db


class Test로그인_관문:
    def test_로그인하지_않으면_막힌다(self, client):
        """허용목록에 없는 `/api` 경로라 관문이 먼저 막는다."""
        응답 = client.post("/api/feedback", json={"content": "여기 좋네요"})

        assert 응답.status_code == 401
        assert 응답.json()["code"] == "UNAUTHORIZED"


class Test접수:
    def test_보낸_사람과_함께_넘긴다(self, 로그인_client, mocker):
        받은 = mocker.patch.object(application, "receive")

        응답 = 로그인_client.post("/api/feedback", json={"content": "차트가 느려요"})

        assert 응답.json()["status"] == 201
        _, 사용자, 본문, _ = 받은.call_args.args
        assert 사용자.id == 7
        assert 본문 == "차트가 느려요"

    def test_빈_의견은_받지_않는다(self, 로그인_client, mocker):
        받은 = mocker.patch.object(application, "receive")

        응답 = 로그인_client.post("/api/feedback", json={"content": "   "})

        assert 응답.json()["code"] == "INVALID_PARAMETER"
        받은.assert_not_called()

    def test_너무_긴_의견은_받지_않는다(self, 로그인_client, mocker):
        받은 = mocker.patch.object(application, "receive")

        응답 = 로그인_client.post("/api/feedback", json={"content": "가" * 501})

        assert 응답.json()["code"] == "INVALID_PARAMETER"
        받은.assert_not_called()


class Test남는_기록:
    def test_앞뒤_공백을_털고_남긴다(self):
        """알림 문구가 저장된 값을 그대로 읽으므로 들어갈 때 털어야 한다."""
        의견 = Feedback.write(7, "  줄 간격이 좁아요\n", datetime(2026, 9, 19, 21, 0))

        assert 의견.content == "줄 간격이 좁아요"
        assert 의견.user_id == 7

    def test_옛_세션이라도_의견은_남는다(self):
        """대체 키 이전 세션에는 내부 식별자가 없다 — 누구인지 몰라도 내용은 잃지 않는다."""
        의견 = Feedback.write(None, "잘 쓰고 있습니다", datetime(2026, 9, 19, 21, 0))

        assert 의견.user_id is None
        assert 의견.content == "잘 쓰고 있습니다"


def 알림_질의() -> str:
    """Grafana 규칙 파일에 적힌 SQL을 그대로 꺼낸다 — 베끼면 같이 낡는다."""
    규칙 = yaml.safe_load(
        (Path(__file__).parents[2] / "../observability/grafana/provisioning/alerting/rules.yaml")
        .resolve()
        .read_text()
    )
    의견_규칙 = next(g for g in 규칙["groups"] if g["name"] == "의견")["rules"][0]
    return next(d for d in 의견_규칙["data"] if d["refId"] == "QUERY")["model"]["rawSql"]


@pytest.fixture
def 빈_의견_테이블(통합_db):
    Feedback.__table__.create(db.get_engine(), checkfirst=True)
    with db.get_session_factory()() as s:
        s.query(Feedback).delete()
        s.commit()
    yield


@pytest.mark.integration
class Test알림_질의:
    """Slack 알림은 Grafana가 이 SQL로 `feedback`을 읽어 만든다.

    규칙 파일이 컬럼 이름을 하드코딩하므로, 테이블을 고치면 코드는 멀쩡한 채로
    **알림만 조용히 멎는다.** 그 조합을 여기서 한 번 맞춰 본다.
    """

    def test_방금_들어온_의견을_문구에_필요한_모양으로_돌려준다(self, 빈_의견_테이블):
        보낸이 = CurrentUser(google_sub="sub", email="a@example.com", id=7)
        with db.get_session_factory()() as 세션:
            application.receive(세션, 보낸이, "차트가\n느려요", datetime.now())

            행 = 세션.execute(text(알림_질의())).mappings().one()

        assert 행["user"] == "7"
        # 줄바꿈이 남으면 Slack 문구가 줄 단위로 깨진다
        assert 행["excerpt"] == "차트가 느려요"
        assert 행["value"] == 1

    def test_열흘_전_의견은_다시_알리지_않는다(self, 빈_의견_테이블):
        """창이 10분이다 — 지난 것이 섞여 오면 매분 같은 알림이 울린다."""
        with db.get_session_factory()() as 세션:
            application.receive(세션, CurrentUser("sub", "a@example.com", 7), "옛날 의견", datetime(2026, 9, 9, 10, 0))

            assert 세션.execute(text(알림_질의())).mappings().all() == []
