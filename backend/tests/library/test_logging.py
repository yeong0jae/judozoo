"""예상된 실패에 스택트레이스를 붙이지 않는다.

브로커 토큰 백오프는 버그가 아니라 상태다. 화면이 5초마다 폴링하고 폴러가 10초마다 도는데
트레이스를 찍으면 로그가 트레이스로 뒤덮인다 — 2026-09-12에 한 시간에 28,000줄이 쌓였다.
"""

import logging

from backend.library.exception import BrokerTokenUnavailable
from backend.library.logging_config import QuietExpectedFailures, QuietProbeAccessLogs


def 기록(msg, args=(), exc=None) -> logging.LogRecord:
    info = None
    if exc is not None:
        try:
            raise exc
        except type(exc):
            import sys
            info = sys.exc_info()
    return logging.LogRecord("t", logging.ERROR, "f.py", 1, msg, args, info)


필터 = QuietExpectedFailures()
프로브필터 = QuietProbeAccessLogs()


class Test백오프_예외:
    def test_트레이스를_떼어낸다(self):
        r = 기록("키움 업종 지수 조회 실패", exc=BrokerTokenUnavailable("백오프 중 — 52초"))

        필터.filter(r)

        assert r.exc_info is None
        assert r.exc_text is None

    def test_사유는_메시지에_남긴다(self):
        r = 기록("키움 업종 지수 조회 실패", exc=BrokerTokenUnavailable("백오프 중 — 52초"))

        필터.filter(r)

        assert r.getMessage() == "키움 업종 지수 조회 실패 — 백오프 중 — 52초"

    def test_포맷_인자가_있어도_깨지지_않는다(self):
        r = 기록("키움 조회 실패 inds_cd=%s", ("001",), BrokerTokenUnavailable("백오프 중"))

        필터.filter(r)

        assert r.getMessage() == "키움 조회 실패 inds_cd=001 — 백오프 중"

    def test_예외_문구에_퍼센트가_있어도_깨지지_않는다(self):
        r = 기록("조회 실패", exc=BrokerTokenUnavailable("한도 100% 초과"))

        필터.filter(r)

        assert r.getMessage() == "조회 실패 — 한도 100% 초과"


class Test그_외_예외:
    def test_진짜_오류는_트레이스를_보존한다(self):
        r = 기록("분봉 파싱 실패", exc=ValueError("예상 못한 응답"))

        필터.filter(r)

        assert r.exc_info is not None
        assert r.getMessage() == "분봉 파싱 실패"

    def test_예외가_없는_기록은_그대로_둔다(self):
        r = 기록("평범한 로그")

        필터.filter(r)

        assert r.exc_info is None
        assert r.getMessage() == "평범한 로그"

    def test_필터는_기록을_버리지_않는다(self):
        assert 필터.filter(기록("x", exc=BrokerTokenUnavailable("y"))) is True
        assert 필터.filter(기록("x", exc=ValueError("y"))) is True


class TestJSON_포맷:
    """운영 로그는 한 줄 JSON이다. Loki에서 `| json | level="ERROR"` 로 거르기 위한 것."""

    def 포맷(self, record) -> dict:
        import json

        from backend.library.logging_config import JsonFormatter

        return json.loads(JsonFormatter().format(record))

    def test_한_줄에_기본_필드를_싣는다(self):
        d = self.포맷(기록("주도주 후보 %s건", ("12",)))

        assert d["level"] == "ERROR"
        assert d["logger"] == "t"
        assert d["msg"] == "주도주 후보 12건"

    def test_시각에_오프셋을_붙인다(self):
        """오프셋이 없으면 Loki가 KST 벽시계를 UTC로 읽어 9시간 어긋난다."""
        d = self.포맷(기록("x"))

        assert d["ts"][-6] in "+-"

    def test_한글이_이스케이프되지_않는다(self):
        import json

        from backend.library.logging_config import JsonFormatter

        line = JsonFormatter().format(기록("분봉 파싱 실패"))

        assert "분봉 파싱 실패" in line
        assert json.loads(line)["msg"] == "분봉 파싱 실패"

    def test_extra로_넘긴_값을_필드로_싣는다(self):
        r = 기록("접속")
        r.user = "u-1234"

        assert self.포맷(r)["user"] == "u-1234"

    def test_진짜_오류는_트레이스를_싣는다(self):
        d = self.포맷(기록("분봉 파싱 실패", exc=ValueError("예상 못한 응답")))

        assert "Traceback" in d["exc"]

    def test_예외가_없으면_exc_필드가_없다(self):
        assert "exc" not in self.포맷(기록("평범한 로그"))


class Test액세스_로그_흡수:
    """uvicorn은 자기 로거에 핸들러를 달고 propagate를 끈다 — 그대로 두면 액세스 로그만 딴 모양이다."""

    def test_핸들러를_걷고_루트로_흘려보낸다(self):
        import logging as _logging

        from backend.library.logging_config import configure_logging

        access = _logging.getLogger("uvicorn.access")
        access.addHandler(_logging.NullHandler())
        access.propagate = False

        configure_logging()

        assert access.handlers == []
        assert access.propagate is True


class Test프로브_액세스_로그:
    """healthcheck와 메트릭 스크레이프가 하루 11,000줄을 찍는다 — 전부 200이고 읽을 것이 없다."""

    def 액세스(self, path: str, status: int, method: str = "GET") -> logging.LogRecord:
        return logging.LogRecord(
            "uvicorn.access",
            logging.INFO,
            "f.py",
            1,
            '%s - "%s %s HTTP/%s" %d',
            ("127.0.0.1:56176", method, path, "1.1", status),
            None,
        )

    def test_정상_응답한_헬스체크는_버린다(self):
        assert 프로브필터.filter(self.액세스("/health", 200)) is False

    def test_정상_응답한_메트릭_스크레이프는_버린다(self):
        assert 프로브필터.filter(self.액세스("/metrics", 200)) is False

    def test_실패한_헬스체크는_남긴다(self):
        """6번 연속 실패하면 배포 스크립트가 롤백을 건다 — 그 순간이야말로 봐야 한다."""
        assert 프로브필터.filter(self.액세스("/health", 503)) is True

    def test_보통_요청은_그대로_남긴다(self):
        assert 프로브필터.filter(self.액세스("/api/market/kospi", 200)) is True

    def test_경로가_비슷할_뿐인_요청은_남긴다(self):
        assert 프로브필터.filter(self.액세스("/api/health-report", 200)) is True

    def test_액세스_로그가_아닌_기록은_건드리지_않는다(self):
        assert 프로브필터.filter(기록("주도주 스캔 시작")) is True


class Test사용자_필드:
    """로그 한 줄마다 누구의 요청이었는지 남긴다. 여정을 이어 보려면 이게 있어야 한다."""

    def 포맷(self, record) -> dict:
        import json

        from backend.library.logging_config import JsonFormatter

        return json.loads(JsonFormatter().format(record))

    def test_로그인한_사용자를_필드로_싣는다(self):
        from backend.library.logging_config import bind_user

        bind_user(7)

        assert self.포맷(기록("주도주 후보 조회"))["user"] == 7

    def test_비로그인이면_필드가_아예_없다(self):
        """스케줄러·기동 로그도 여기 해당한다. 항상 있다고 보고 쿼리를 짜면 그 줄들이 사라진다."""
        from backend.library.logging_config import bind_user

        bind_user(None)

        assert "user" not in self.포맷(기록("주도주 스캔 시작"))

    def test_다음_요청이_이전_사용자를_물려받지_않는다(self):
        from backend.library.logging_config import bind_user

        bind_user(42)
        bind_user(None)

        assert "user" not in self.포맷(기록("공개 화면 조회"))


class Test요청_필드:
    """어느 요청이었는지 한 줄에 남긴다. 트레이스백만으로는 URL을 알 수 없다."""

    def 포맷(self, record) -> dict:
        import json

        from backend.library.logging_config import JsonFormatter

        return json.loads(JsonFormatter().format(record))

    def test_메서드와_경로를_함께_싣는다(self):
        from backend.library.logging_config import bind_request

        bind_request("GET", "/api/overseas-leading-stocks/leaders")

        d = self.포맷(기록("Unhandled exception"))
        assert d["request"] == "GET /api/overseas-leading-stocks/leaders"

    def test_요청_밖에서는_필드가_아예_없다(self):
        """스케줄러·기동 로그가 여기 해당한다."""
        from backend.library.logging_config import bind_request

        bind_request(None, None)

        assert "request" not in self.포맷(기록("주도주 스캔 시작"))

    def test_다음_요청이_이전_경로를_물려받지_않는다(self):
        from backend.library.logging_config import bind_request

        bind_request("GET", "/api/market/kospi")
        bind_request(None, None)

        assert "request" not in self.포맷(기록("스케줄러 기동"))
