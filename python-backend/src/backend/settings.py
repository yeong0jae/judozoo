"""애플리케이션 설정.

Kotlin의 `@ConfigurationProperties` + `application.yaml`에 대응한다.
환경변수 이름은 기존 배포(`backend/.env`, Secret Manager)와 **그대로 맞춘다** —
이관 중에는 두 백엔드가 같은 시크릿을 읽어야 하기 때문이다.
"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

_BASE = SettingsConfigDict(extra="ignore", case_sensitive=False)


class DatabaseSettings(BaseSettings):
    model_config = _BASE

    host: str = Field("localhost", validation_alias="DB_HOST")
    port: int = Field(33061, validation_alias="DB_PORT")
    name: str = Field("trading", validation_alias="DB_NAME")
    username: str = Field("root", validation_alias="DB_USERNAME")
    password: str = Field("", validation_alias="DB_PASSWORD")

    @property
    def url(self) -> str:
        return (
            f"mysql+pymysql://{self.username}:{self.password}"
            f"@{self.host}:{self.port}/{self.name}?charset=utf8mb4"
        )


class KisSettings(BaseSettings):
    model_config = _BASE

    base_url: str = "https://openapi.koreainvestment.com:9443"
    app_key: str = Field("", validation_alias="REAL_KIS_APP_KEY")
    app_secret: str = Field("", validation_alias="REAL_KIS_APP_SECRET")


class KiwoomSettings(BaseSettings):
    model_config = _BASE

    base_url: str = "https://api.kiwoom.com"
    app_key: str = Field("", validation_alias="REAL_KIWOOM_APP_KEY")
    app_secret: str = Field("", validation_alias="REAL_KIWOOM_APP_SECRET")
    # 조회(ka*) 공유 리미터 허용량. 한도가 초당 5건이라 기본값은 여유를 두지 않았다.
    # 여유가 필요하면 4 등으로 낮춘다.
    query_permits_per_second: int = Field(5, validation_alias="KIWOOM_QUERY_PERMITS_PER_SECOND")


class TossSettings(BaseSettings):
    model_config = _BASE

    base_url: str = "https://openapi.tossinvest.com"
    client_id: str = Field("", validation_alias="REAL_TOSS_CLIENT_ID")
    client_secret: str = Field("", validation_alias="REAL_TOSS_CLIENT_SECRET")


class StockMasterSettings(BaseSettings):
    """KIS 종목정보 마스터 파일 (평문 CDN — OpenAPI 토큰/레이트리밋 무관)."""

    model_config = _BASE

    kospi_url: str = "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip"
    kosdaq_url: str = "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip"
    overseas_urls: tuple[str, ...] = (
        "https://new.real.download.dws.co.kr/common/master/nasmst.cod.zip",
        "https://new.real.download.dws.co.kr/common/master/nysmst.cod.zip",
        "https://new.real.download.dws.co.kr/common/master/amsmst.cod.zip",
    )


class LeadingStockCriteria(BaseSettings):
    """주도주 후보 필터 기준값. `leading-stock.criteria.*`와 1:1 대응."""

    model_config = SettingsConfigDict(env_prefix="LEADING_STOCK_CRITERIA_", **_BASE)

    min_market_cap: int = 3000                    # 억원
    max_trading_value_rank: int = 35
    min_daily_price_change_rate: float = 7.0      # %
    max_high_position_drop_rate: float = -5.0     # 60봉 고가 대비 %
    min_minute_volume_increase_rate: float = 500.0
    min_minute_trading_value: int = 5_000_000_000
    max_minute_fluctuation_rate: float = 4.0
    min_program_net_buy: int = -10_000            # 백만원
    max_prev_close_change_rate: float = 25.0
    max_opening_price_change_rate: float = 7.0
    max_theme_rank: int = 5


class SignalEventSettings(BaseSettings):
    """시그널 로그 폴러 전용 — 후보/돌파/스파이크 화면 기본값과 분리된다."""

    model_config = SettingsConfigDict(env_prefix="LEADING_STOCK_SIGNAL_EVENT_", **_BASE)

    min_change_rate: float = -12.0        # 감시 풀 등락률 하한(%)
    poll_interval_millis: int = 10_000
    cooldown_minutes: int = 3             # 같은 종목·타입 재적재 쿨다운


class MarketSignalSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="LEADING_STOCK_MARKET_SIGNAL_", **_BASE)

    poll_interval_millis: int = 120_000
    candle_poll_interval_millis: int = 30_000
    session_start: str = "09:00"          # 지수는 정규장에만 체결
    session_end: str = "15:30"


class Settings(BaseSettings):
    model_config = _BASE

    app_name: str = "backend"
    debug_package: str = "backend"

    database: DatabaseSettings = Field(default_factory=DatabaseSettings)
    kis: KisSettings = Field(default_factory=KisSettings)
    kiwoom: KiwoomSettings = Field(default_factory=KiwoomSettings)
    toss: TossSettings = Field(default_factory=TossSettings)
    stock_master: StockMasterSettings = Field(default_factory=StockMasterSettings)
    criteria: LeadingStockCriteria = Field(default_factory=LeadingStockCriteria)
    signal_event: SignalEventSettings = Field(default_factory=SignalEventSettings)
    market_signal: MarketSignalSettings = Field(default_factory=MarketSignalSettings)


@lru_cache
def get_settings() -> Settings:
    """프로세스당 한 번만 만든다. 테스트에서는 `get_settings.cache_clear()`로 비운다."""
    return Settings()
