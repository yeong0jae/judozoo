"""모의 종가베팅 엔티티 — 사람·베팅·이벤트, 그리고 20:00에 찍어 두는 판.

- `bet_player` — 닉네임. 이메일은 `app_user`에만 있고 여기엔 `user_id`만 둔다(feedback과 같은 이유)
- `closing_bet` — 사람·판마다 한 행. 취소하면 지운다(LIVE 이력은 이벤트에 남는다)
- `closing_bet_event` — 베팅·변경·취소. 판돈·참여 수의 원천이 아니라 LIVE 목록이다
- `bet_round` / `bet_round_stock` — 20:00 스냅샷. 복기는 "체결 시점 그대로"여야 해서 다음 날 다시 계산하지 않는다

순매수는 키움 단위 그대로 **백만원**이다. 억원으로 바꾸는 건 화면 쪽 일이다.
"""

from datetime import date, datetime

from sqlalchemy import JSON, BigInteger, Boolean, Date, DateTime, Float, Index, Integer, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from backend.library.db import Base


class BetPlayer(Base):
    __tablename__ = "bet_player"

    user_id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    nickname: Mapped[str] = mapped_column(String(10), unique=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: 직접 바꾼 시각. 처음 받은 랜덤 닉네임이면 None — 바로 바꿀 수 있다
    renamed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class ClosingBet(Base):
    __tablename__ = "closing_bet"
    __table_args__ = (
        UniqueConstraint("user_id", "trading_day", name="uq_closing_bet_user_day"),
        Index("idx_closing_bet_day", "trading_day", "status"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    #: 판 — 베팅한 거래일
    trading_day: Mapped[date] = mapped_column(Date, nullable=False)
    stock_code: Mapped[str] = mapped_column(String(6), nullable=False)
    stock_name: Mapped[str] = mapped_column(String(100), nullable=False)
    amount_man: Mapped[int] = mapped_column(Integer, nullable=False)
    #: 지금 종목·금액으로 정한 시각 — 동점일 때 줄 순서
    placed_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: open(베팅 중) → filled(20:00 체결) → settled(아침 매도)
    status: Mapped[str] = mapped_column(String(8), nullable=False, default="open")
    buy_price: Mapped[int | None] = mapped_column(Integer, nullable=True)
    shares: Mapped[int | None] = mapped_column(Integer, nullable=True)
    #: (고가 + 저가) ÷ 2 — 0.5원 단위가 나온다
    sell_price: Mapped[float | None] = mapped_column(Numeric(12, 1, asdecimal=False), nullable=True)
    pnl: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    rate: Mapped[float | None] = mapped_column(Float, nullable=True)


class ClosingBetEvent(Base):
    __tablename__ = "closing_bet_event"
    __table_args__ = (Index("idx_closing_bet_event_day", "trading_day", "at"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    trading_day: Mapped[date] = mapped_column(Date, nullable=False)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    stock_name: Mapped[str] = mapped_column(String(100), nullable=False)
    #: 취소는 음수
    amount_man: Mapped[int] = mapped_column(Integer, nullable=False)
    at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class BetRound(Base):
    __tablename__ = "bet_round"

    trading_day: Mapped[date] = mapped_column(Date, primary_key=True)
    snapped_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: 코스피·코스닥 × 외인·기관 × [현물 당일, 현물 5일, 선물 당일, 선물 5일, 마감, 애프터] + 야간선물
    market: Mapped[dict] = mapped_column(JSON, nullable=False)
    players: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    pot_man: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    #: 09:05 랭킹 확정. 늦게 체결된 종목이 있으면 그 뒤
    settled_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class BetRoundStock(Base):
    __tablename__ = "bet_round_stock"
    __table_args__ = (UniqueConstraint("trading_day", "stock_code", name="uq_bet_round_stock"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    trading_day: Mapped[date] = mapped_column(Date, nullable=False)
    stock_code: Mapped[str] = mapped_column(String(6), nullable=False)
    stock_name: Mapped[str] = mapped_column(String(100), nullable=False)
    #: KOSPI / KOSDAQ
    market: Mapped[str] = mapped_column(String(6), nullable=False)
    lead: Mapped[bool] = mapped_column(Boolean, nullable=False)
    nxt: Mapped[bool] = mapped_column(Boolean, nullable=False)
    #: 20:00 종가 = 매수가
    close_price: Mapped[int] = mapped_column(Integer, nullable=False)
    high_price: Mapped[int] = mapped_column(Integer, nullable=False)
    low_price: Mapped[int] = mapped_column(Integer, nullable=False)
    change_rate: Mapped[float] = mapped_column(Float, nullable=False)
    foreign_net: Mapped[int] = mapped_column(BigInteger, nullable=False)
    institution_net: Mapped[int] = mapped_column(BigInteger, nullable=False)
    foreign_5d: Mapped[int] = mapped_column(BigInteger, nullable=False)
    institution_5d: Mapped[int] = mapped_column(BigInteger, nullable=False)
    #: 최근 고점과의 거리(%) — 직전 60거래일 최고가 대비. 일봉이 없으면 None
    recent_high_gap: Mapped[float | None] = mapped_column(Float, nullable=True)
    #: 15:30 정규장 종가 — 애프터 버팀(20:00 종가 ≥ 이 값) 판정. 못 잡았으면 None
    regular_close: Mapped[int | None] = mapped_column(Integer, nullable=True)
    #: {near_high, foreign, institution, market_late, recent_high, after_hold}
    checks: Mapped[dict] = mapped_column(JSON, nullable=False)
    grade: Mapped[str] = mapped_column(String(1), nullable=False)
    crowd: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    pot_man: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    sell_price: Mapped[float | None] = mapped_column(Numeric(12, 1, asdecimal=False), nullable=True)
    #: 실제로 값을 낸 5분의 시작. 창에 체결이 없으면 미뤄진 시각, 끝까지 없으면 None
    sell_window_start: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    rate: Mapped[float | None] = mapped_column(Float, nullable=True)
