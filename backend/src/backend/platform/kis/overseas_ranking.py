"""KIS 해외주식 거래대금순위 (HHDFS76320010) — 거래소별 당일 상위."""

import logging
from dataclasses import dataclass
from typing import Any

from backend.platform.kis.client import auth_headers, get_client

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class OverseasRankItem:
    """KIS 응답 원형 그대로. 숫자 변환은 쓰는 쪽에서 한다.

    `sign`이 등락 방향을 준다(1:상한가 2:상승 3:보합 4:하한가 5:하락).
    `diff`/`rate`는 실측상 **부호가 붙어 온다**(`+0.96`, `-26.87`). 다만 응답에 따라
    다를 수 있으므로, 쓰는 쪽은 `sign`을 기준으로 삼고 값은 크기로 다루는 편이 안전하다.
    """

    rank: str
    excd: str
    symb: str
    name: str
    last: str
    sign: str
    diff: str
    rate: str
    tvol: str
    tamt: str  # 달러 단위
    ename: str


def fetch_trading_value_ranking(excd: str) -> list[OverseasRankItem]:
    """캐시하지 않는다 — 세 거래소를 합친 풀 단위로 `overseasleadingstock.application`이 쥔다."""
    log.info("KIS 해외주식 거래대금순위 조회: excd=%s", excd)
    response = get_client().get(
        "/uapi/overseas-stock/v1/ranking/trade-pbmn",
        params={
            "KEYB": "",
            "AUTH": "",
            "EXCD": excd,
            "NDAY": "0",
            "VOL_RANG": "0",
            "PRC1": "",
            "PRC2": "",
        },
        headers=auth_headers("HHDFS76320010"),
    )
    response.raise_for_status()
    body = response.json()
    if body.get("rt_cd") != "0":
        raise RuntimeError(f"KIS 거래대금순위 오류: {body.get('msg1')} (excd={excd})")
    return [_to_item(row) for row in (body.get("output2") or [])]


def _to_item(row: dict[str, Any]) -> OverseasRankItem:
    def s(key: str) -> str:
        value = row.get(key)
        return str(value) if value is not None else ""

    return OverseasRankItem(
        rank=s("rank"), excd=s("excd"), symb=s("symb"), name=s("name"),
        last=s("last"), sign=s("sign"), diff=s("diff"), rate=s("rate"),
        tvol=s("tvol"), tamt=s("tamt"), ename=s("ename"),
    )
