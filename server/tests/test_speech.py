import pytest

from app.speech import ko_count, read_won


@pytest.mark.parametrize(
    "amount, spoken",
    # 앱 KoreanSpeechTest.readWon_readsPricesAsSpoken 과 같은 사례. 두 구현이 어긋나지 않게 한다
    [
        (9_900, "구천구백 원"),
        (18_500, "만 팔천오백 원"),
        (21_500, "이만 천오백 원"),
        (40_000, "사만 원"),
        (10_000, "만 원"),
        (110_000, "십일만 원"),
        (1_000, "천 원"),
        (0, "영 원"),
        (15_800, "만 오천팔백 원"),
    ],
)
def test_read_won_matches_app(amount, spoken):
    assert read_won(amount) == spoken


def test_read_won_rejects_out_of_range():
    with pytest.raises(ValueError):
        read_won(-1)


def test_ko_count_matches_app():
    assert [ko_count(n) for n in (1, 2, 3, 4, 9, 10, 12)] == ["한", "두", "세", "네", "아홉", "10", "12"]
