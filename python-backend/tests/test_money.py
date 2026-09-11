"""한국 금액 표기 — Kotlin `FormatUtils` 이관. 전시 문자열이라 글자까지 같아야 한다."""

from backend.library.money import format_korean_money, format_korean_money_with_man, format_price


class Test억조_표기:
    def test_조_단위는_억이_없으면_조만_쓴다(self):
        assert format_korean_money(1_073_000_000_000_000) == "1,073조원"

    def test_조와_억이_함께_있으면_둘_다_쓴다(self):
        assert format_korean_money(1_000_500_000_000) == "1조 5억원"

    def test_억_단위는_억으로_끊는다(self):
        assert format_korean_money(300_000_000_000) == "3,000억원"

    def test_억_미만은_원으로_쓴다(self):
        assert format_korean_money(5_000) == "5,000원"

    def test_음수는_부호를_앞에_붙인다(self):
        assert format_korean_money(-300_000_000_000) == "-3,000억원"


class Test만원까지_표기:
    def test_억과_만을_함께_쓴다(self):
        assert format_korean_money_with_man(1_234_567_890) == "12억 3,456만원"

    def test_만_단위가_0이면_생략한다(self):
        assert format_korean_money_with_man(1_200_000_000) == "12억원"

    def test_억_미만_만_단위(self):
        assert format_korean_money_with_man(50_000) == "5만원"

    def test_만_미만은_원으로_쓴다(self):
        assert format_korean_money_with_man(9_999) == "9,999원"

    def test_음수는_부호를_앞에_붙인다(self):
        assert format_korean_money_with_man(-1_234_567_890) == "-12억 3,456만원"


class Test가격_표기:
    def test_천단위_쉼표를_넣는다(self):
        assert format_price(71_500) == "71,500원"
