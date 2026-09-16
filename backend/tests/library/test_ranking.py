"""두 축 백분위 기하평균 순위."""

from backend.library.ranking import top_balanced


def 항목(이름: str, 축1: float, 축2: float) -> tuple[str, float, float]:
    return (이름, 축1, 축2)


def 상위(items, count, 첫축_무게=0.5):
    return [
        name
        for name, _, _ in top_balanced(
            items, lambda i: i[1], lambda i: i[2], count, 첫축_무게
        )
    ]


class Test두_축_균형_순위:
    def test_두_축이_모두_높은_것이_한쪽만_높은_것을_이긴다(self):
        items = [
            항목("둘다중상", 80, 80),
            항목("한쪽만최고", 100, 10),
            항목("반대쪽만최고", 10, 100),
        ]

        assert 상위(items, 1) == ["둘다중상"]

    def test_한_축의_극단값이_순위를_끌고_가지_못한다(self):
        """크기를 그대로 쓰면 '축2 = 1000'이 1위가 된다. 백분위는 등수만 보므로 한 칸 차이다."""
        items = [
            항목("대장", 1000, 12),
            항목("극단값", 10, 1000),
            항목("둘다상위", 900, 15),
        ]

        assert 상위(items, 2) == ["둘다상위", "대장"]

    def test_요청한_수보다_적으면_있는_만큼만_준다(self):
        assert 상위([항목("하나", 1, 1)], 5) == ["하나"]

    def test_빈_목록은_빈_결과다(self):
        assert 상위([], 3) == []

    def test_첫_축에_무게를_더_주면_그_축이_높은_쪽이_올라온다(self):
        items = [항목("축1우세", 100, 10), 항목("축2우세", 10, 100)]

        assert 상위(items, 1, 0.6) == ["축1우세"]
        assert 상위(items, 1, 0.4) == ["축2우세"]

    def test_점수가_같으면_먼저_온_것이_앞선다(self):
        """같은 입력이 같은 순서를 내야 새로고침마다 줄이 바뀌지 않는다."""
        items = [항목("먼저", 10, 20), 항목("나중", 20, 10)]

        assert 상위(items, 2) == ["먼저", "나중"]
