from sqlalchemy.engine import make_url

from backend.settings import Settings, get_settings


class Test브로커_시크릿:
    """환경변수 이름이 기존 배포(`backend/.env`)와 어긋나면 이관 중 인증이 조용히 실패한다."""

    def test_기존_환경변수_이름을_그대로_읽는다(self, monkeypatch):
        monkeypatch.setenv("REAL_KIS_APP_KEY", "kis-키")
        monkeypatch.setenv("REAL_KIWOOM_APP_SECRET", "kiwoom-비밀")
        monkeypatch.setenv("REAL_TOSS_CLIENT_ID", "toss-아이디")

        settings = Settings()

        assert settings.kis.app_key == "kis-키"
        assert settings.kiwoom.app_secret == "kiwoom-비밀"
        assert settings.toss.client_id == "toss-아이디"

    def test_값이_없으면_빈_문자열이라_기동은_막지_않는다(self, monkeypatch):
        monkeypatch.delenv("REAL_KIS_APP_KEY", raising=False)

        assert Settings().kis.app_key == ""


class Test필터_기준값:
    def test_기본값이_기존_설정과_같다(self):
        criteria = Settings().criteria

        assert criteria.min_market_cap == 3000
        assert criteria.max_trading_value_rank == 35
        assert criteria.min_daily_price_change_rate == 7.0

    def test_환경변수로_덮어쓸_수_있다(self, monkeypatch):
        monkeypatch.setenv("LEADING_STOCK_CRITERIA_MAX_TRADING_VALUE_RANK", "50")

        assert Settings().criteria.max_trading_value_rank == 50


class TestDB_접속정보:
    def test_접속_URL을_조립한다(self, monkeypatch):
        monkeypatch.setenv("DB_HOST", "mysql")
        monkeypatch.setenv("DB_PORT", "3306")
        monkeypatch.setenv("DB_USERNAME", "root")
        monkeypatch.setenv("DB_PASSWORD", "비밀")

        parsed = make_url(Settings().database.url)

        assert (parsed.host, parsed.port, parsed.database) == ("mysql", 3306, "trading")
        assert (parsed.username, parsed.password) == ("root", "비밀")

    def test_비밀번호의_특수문자가_URL_구획을_깨뜨리지_않는다(self, monkeypatch):
        """`/`나 `@`가 날것으로 들어가면 호스트·DB 이름 구획이 밀려 엉뚱한 곳에 붙는다."""
        monkeypatch.setenv("DB_HOST", "10.0.0.1")
        monkeypatch.setenv("DB_PORT", "3306")
        monkeypatch.setenv("DB_USERNAME", "judozoo_app")
        monkeypatch.setenv("DB_PASSWORD", "p@ss/w:rd#1")

        parsed = make_url(Settings().database.url)

        assert parsed.password == "p@ss/w:rd#1"
        assert (parsed.host, parsed.port, parsed.database) == ("10.0.0.1", 3306, "trading")


class Test설정_캐시:
    def test_같은_인스턴스를_재사용한다(self):
        get_settings.cache_clear()

        assert get_settings() is get_settings()
