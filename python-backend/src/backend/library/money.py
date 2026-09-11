"""한국 금액 표기. Kotlin `library.format.FormatUtils`에 대응.

필터 설명·실적값을 사람이 읽는 문자열로 만든다. 전시 문자열이므로 Kotlin과
**글자 단위로 같아야** 한다 — 프론트가 그대로 뿌린다.
"""


def _comma(n: int) -> str:
    return f"{n:,}"


def format_korean_money(amount: int) -> str:
    """300_000_000_000 → "3,000억원" / 1_073_000_000_000_000 → "1,073조원"."""
    sign = "-" if amount < 0 else ""
    a = abs(amount)
    if a >= 1_000_000_000_000:
        jo = a // 1_000_000_000_000
        eok = (a % 1_000_000_000_000) // 100_000_000
        if eok > 0:
            return f"{sign}{_comma(jo)}조 {_comma(eok)}억원"
        return f"{sign}{_comma(jo)}조원"
    if a >= 100_000_000:
        return f"{sign}{_comma(a // 100_000_000)}억원"
    return f"{sign}{_comma(a)}원"


def format_korean_money_with_man(amount: int) -> str:
    """1_234_567_890 → "12억 3,456만원". 억 미만 잔돈까지 보여야 하는 수급값에 쓴다."""
    sign = "-" if amount < 0 else ""
    a = abs(amount)
    if a >= 1_000_000_000_000:
        jo = a // 1_000_000_000_000
        eok = (a % 1_000_000_000_000) // 100_000_000
        man = (a % 100_000_000) // 10_000
        out = f"{sign}{_comma(jo)}조"
        if eok > 0:
            out += f" {_comma(eok)}억"
        if man > 0:
            out += f" {_comma(man)}만"
        return out + "원"
    if a >= 100_000_000:
        eok = a // 100_000_000
        man = (a % 100_000_000) // 10_000
        out = f"{sign}{_comma(eok)}억"
        if man > 0:
            out += f" {_comma(man)}만"
        return out + "원"
    if a >= 10_000:
        return f"{sign}{_comma(a // 10_000)}만원"
    return f"{sign}{_comma(a)}원"


def format_price(price: int) -> str:
    return f"{_comma(price)}원"
