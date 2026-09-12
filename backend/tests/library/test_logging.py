"""예상된 실패에 스택트레이스를 붙이지 않는다.

브로커 토큰 백오프는 버그가 아니라 상태다. 화면이 5초마다 폴링하고 폴러가 10초마다 도는데
트레이스를 찍으면 로그가 트레이스로 뒤덮인다 — 2026-09-12에 한 시간에 28,000줄이 쌓였다.
"""

import logging

from backend.library.exception import BrokerTokenUnavailable
from backend.library.logging_config import QuietExpectedFailures


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
