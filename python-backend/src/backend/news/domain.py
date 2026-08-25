"""종목 뉴스 도메인.

외부 의존 없음 — 시간·HTTP·설정을 직접 만지지 않고 파라미터로만 받는다.
"""

from dataclasses import dataclass
from datetime import datetime

# 장내·코스닥·프리보드·기타·코넥스 공시
_DISCLOSURE_CODES = frozenset({"F", "G", "H", "I", "N"})


@dataclass(frozen=True)
class StockNews:
    """종목 관련 뉴스·공시 한 건 — KIS 종합 시황/공시. 제목만 제공되고 원문 링크는 없다.

    공시는 언론 기사와 같은 목록에 섞여 오므로, 제공 업체 코드로 스스로 구분한다.
    """

    seq_no: str
    title: str
    source: str  # 표시용 출처명 — 언론사명 또는 "공시"
    provider_code: str
    published_at: datetime

    @property
    def disclosure(self) -> bool:
        """언론 기사가 아니라 거래소·코스닥 등의 공시인지."""
        return self.provider_code in _DISCLOSURE_CODES
