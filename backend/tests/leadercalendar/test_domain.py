"""국내 날짜 칸에 붙는 해외장 — 직전 평일."""

from datetime import date

from backend.leadercalendar.domain import previous_weekday


class Test직전_해외장:
    def test_화요일_칸에는_월요일_해외장이_붙는다(self):
        assert previous_weekday(date(2026, 9, 22)) == date(2026, 9, 21)

    def test_월요일_칸에는_금요일_해외장이_붙는다(self):
        assert previous_weekday(date(2026, 9, 28)) == date(2026, 9, 25)

    def test_달의_첫날_칸은_전달의_해외장을_본다(self):
        assert previous_weekday(date(2026, 9, 1)) == date(2026, 8, 31)
