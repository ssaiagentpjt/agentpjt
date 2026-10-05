from datetime import date

from app.delivery import arrive

SAT = date(2026, 10, 3)  # 토요일


def test_tomorrow_and_day_after():
    assert arrive(SAT, 1) == ("내일 10월 4일(일)", "내일")
    assert arrive(SAT, 2) == ("모레 10월 5일(월)", "모레")


def test_later_days_read_weekday():
    assert arrive(SAT, 3) == ("10월 6일(화)", "10월 6일 화요일에")


def test_month_rollover():
    assert arrive(date(2026, 10, 30), 3) == ("11월 2일(월)", "11월 2일 월요일에")
