"""도착 예정일을 화면 표기와 읽는 말로 바꾼다. 날짜 기준은 한국 시간."""

from datetime import date, datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
_WEEKDAYS = "월화수목금토일"


def today_kst() -> date:
    return datetime.now(KST).date()


def arrive(today: date, delivery_days: int) -> tuple[str, str]:
    """(화면 표기, 읽는 말). 예: (내일 10월 6일(화), 내일), (10월 8일(목), 10월 8일 목요일에)

    읽는 말은 앱 대본의 "{arriveSpoken} 도착해요" 에 들어간다.
    """
    d = today + timedelta(days=delivery_days)
    w = _WEEKDAYS[d.weekday()]
    md = f"{d.month}월 {d.day}일"
    if delivery_days == 1:
        return f"내일 {md}({w})", "내일"
    if delivery_days == 2:
        return f"모레 {md}({w})", "모레"
    return f"{md}({w})", f"{md} {w}요일에"
