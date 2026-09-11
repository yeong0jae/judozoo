"""KIS 종목 마스터 파일 파싱 — 고정폭(국내) / 탭 구분(해외), 인코딩 MS949.

Kotlin 쪽에 대응 테스트가 없던 영역이다. 파싱 실패가 조용히 빈 카탈로그로 이어지므로
포맷 함정을 여기서 잡는다.
"""

import io
import zipfile

import httpx
import pytest
import respx

from backend.settings import get_settings
from backend.stock import infrastructure
from backend.stock.domain import Market

KOSPI_URL = "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip"
KOSDAQ_URL = "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip"
NAS_URL = "https://new.real.download.dws.co.kr/common/master/nasmst.cod.zip"
NYS_URL = "https://new.real.download.dws.co.kr/common/master/nysmst.cod.zip"
AMS_URL = "https://new.real.download.dws.co.kr/common/master/amsmst.cod.zip"

KOSPI_PART2 = 228
KOSDAQ_PART2 = 222


def 압축(text: str, name: str = "master.mst") -> httpx.Response:
    """MS949로 인코딩해 zip 한 개 엔트리로 만든다 — 실제 CDN 응답과 같은 모양."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(name, text.encode("ms949"))
    return httpx.Response(200, content=buf.getvalue())


def 국내행(short_code: str, standard_code: str, name: str, part2_width: int) -> str:
    """part1(단축코드 9 + 표준코드 12 + 한글명) + 고정폭 part2."""
    return f"{short_code:<9}{standard_code:<12}{name}" + "X" * part2_width


def 해외행(exchange: str, symbol: str, korean: str, english: str) -> str:
    cols = ["", "", exchange, "", symbol, "", korean, english, "extra"]
    return "\t".join(cols)


@pytest.fixture(autouse=True)
def 설정_초기화():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


class Test국내_마스터:
    @respx.mock
    def test_고정폭_라인에서_코드와_종목명을_뽑는다(self, respx_mock):
        respx_mock.get(KOSPI_URL).mock(
            return_value=압축(국내행("005930", "KR7005930003", "삼성전자", KOSPI_PART2))
        )
        respx_mock.get(KOSDAQ_URL).mock(return_value=압축(""))

        종목들 = infrastructure.fetch_domestic()

        assert len(종목들) == 1
        assert (종목들[0].short_code, 종목들[0].standard_code) == ("005930", "KR7005930003")
        assert 종목들[0].name == "삼성전자"
        assert 종목들[0].market == Market.KOSPI

    @respx.mock
    def test_코스피와_코스닥은_part2_폭이_달라_각자_잘린다(self, respx_mock):
        """폭을 잘못 쓰면 종목명 끝에 part2 쓰레기가 붙는다."""
        respx_mock.get(KOSPI_URL).mock(
            return_value=압축(국내행("005930", "KR7005930003", "삼성전자", KOSPI_PART2))
        )
        respx_mock.get(KOSDAQ_URL).mock(
            return_value=압축(국내행("035720", "KR7035720002", "카카오", KOSDAQ_PART2))
        )

        종목들 = infrastructure.fetch_domestic()

        이름 = {s.short_code: s.name for s in 종목들}
        assert 이름 == {"005930": "삼성전자", "035720": "카카오"}
        시장 = {s.short_code: s.market for s in 종목들}
        assert 시장 == {"005930": Market.KOSPI, "035720": Market.KOSDAQ}

    @respx.mock
    def test_한글이_깨지지_않는다(self, respx_mock):
        """MS949로 읽어야 한다 — UTF-8로 읽으면 종목명이 깨진다."""
        respx_mock.get(KOSPI_URL).mock(
            return_value=압축(국내행("068270", "KR7068270008", "셀트리온", KOSPI_PART2))
        )
        respx_mock.get(KOSDAQ_URL).mock(return_value=압축(""))

        assert infrastructure.fetch_domestic()[0].name == "셀트리온"

    @respx.mock
    def test_길이가_모자란_줄은_건너뛴다(self, respx_mock):
        """파일 끝 개행·머리말 등 part2 폭에 못 미치는 줄이 섞여 들어온다."""
        본문 = 국내행("005930", "KR7005930003", "삼성전자", KOSPI_PART2)
        respx_mock.get(KOSPI_URL).mock(return_value=압축(f"짧은줄\n{본문}\n"))
        respx_mock.get(KOSDAQ_URL).mock(return_value=압축(""))

        assert [s.short_code for s in infrastructure.fetch_domestic()] == ["005930"]

    @respx.mock
    def test_단축코드가_빈_줄은_건너뛴다(self, respx_mock):
        respx_mock.get(KOSPI_URL).mock(
            return_value=압축(국내행("", "KR7005930003", "이름만있음", KOSPI_PART2))
        )
        respx_mock.get(KOSDAQ_URL).mock(return_value=압축(""))

        assert infrastructure.fetch_domestic() == []


class Test해외_마스터:
    def 세_거래소_응답(self, respx_mock, nas: str = "", nys: str = "", ams: str = ""):
        respx_mock.get(NAS_URL).mock(return_value=압축(nas))
        respx_mock.get(NYS_URL).mock(return_value=압축(nys))
        respx_mock.get(AMS_URL).mock(return_value=압축(ams))

    @respx.mock
    def test_탭_구분에서_거래소_심볼_이름을_뽑는다(self, respx_mock):
        self.세_거래소_응답(respx_mock, nas=해외행("NAS", "AAPL", "애플", "APPLE INC"))

        종목들 = infrastructure.fetch_overseas()

        assert len(종목들) == 1
        s = 종목들[0]
        assert (s.exchange, s.symbol, s.name, s.english_name) == ("NAS", "AAPL", "애플", "APPLE INC")

    @respx.mock
    def test_세_거래소를_모두_합친다(self, respx_mock):
        self.세_거래소_응답(
            respx_mock,
            nas=해외행("NAS", "AAPL", "애플", "APPLE INC"),
            nys=해외행("NYS", "JPM", "제이피모건", "JPMORGAN CHASE"),
            ams=해외행("AMS", "IMO", "임페리얼오일", "IMPERIAL OIL"),
        )

        assert {s.exchange for s in infrastructure.fetch_overseas()} == {"NAS", "NYS", "AMS"}

    @respx.mock
    def test_한글명이_비면_심볼로_대체한다(self, respx_mock):
        self.세_거래소_응답(respx_mock, nas=해외행("NAS", "TSLA", "", "TESLA INC"))

        assert infrastructure.fetch_overseas()[0].name == "TSLA"

    @respx.mock
    def test_영문명이_길면_컬럼_길이까지만_남긴다(self, respx_mock):
        """옵션 만기 문구 등이 붙어 120자를 넘는 경우가 있다 — 넘치면 INSERT가 깨진다."""
        self.세_거래소_응답(respx_mock, nas=해외행("NAS", "XXXX", "긴이름", "A" * 200))

        assert len(infrastructure.fetch_overseas()[0].english_name) == 120

    @respx.mock
    def test_컬럼이_모자란_줄은_건너뛴다(self, respx_mock):
        self.세_거래소_응답(respx_mock, nas="NAS\tAAPL\n" + 해외행("NAS", "MSFT", "마이크로소프트", "MICROSOFT"))

        assert [s.symbol for s in infrastructure.fetch_overseas()] == ["MSFT"]

    @respx.mock
    def test_심볼이_빈_줄은_건너뛴다(self, respx_mock):
        self.세_거래소_응답(respx_mock, nas=해외행("NAS", "", "이름만", "NAME ONLY"))

        assert infrastructure.fetch_overseas() == []


class Test다운로드_실패:
    @respx.mock
    def test_오류_응답이면_예외로_올린다(self, respx_mock):
        """폴백(직전 DB 데이터)은 상위에서 처리한다 — 여기선 실패를 숨기지 않는다."""
        respx_mock.get(KOSPI_URL).mock(return_value=httpx.Response(500))

        with pytest.raises(httpx.HTTPStatusError):
            infrastructure.fetch_domestic()

    @respx.mock
    def test_zip이_비어있으면_예외로_올린다(self, respx_mock):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w"):
            pass
        respx_mock.get(KOSPI_URL).mock(return_value=httpx.Response(200, content=buf.getvalue()))

        with pytest.raises(ValueError):
            infrastructure.fetch_domestic()
