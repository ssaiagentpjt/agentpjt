"""주문 확인·완료·취소 때 읽어 줄 한 줄 요약(summaryText).

앱 대본(Scripts.kt)과 같은 말투로 쓴다. 금액은 read_won, 수량은 ko_count, 도착일은 delivery.arrive 를 써서
온디바이스 모델이 숫자를 직접 읽지 않게 한다.
"""

from dataclasses import dataclass
from datetime import date

from .delivery import arrive
from .speech import ko_count, read_won


@dataclass(frozen=True)
class SpokenLine:
    name: str
    options: dict[str, str]
    quantity: int
    delivery_days: int


def _item(line: SpokenLine) -> str:
    """"새치 염색약 1회분 흑갈색 두 개" """
    opts = "".join(f" {v}" for v in line.options.values())
    return f"{line.name}{opts} {ko_count(line.quantity)} 개"


def _items(lines: list[SpokenLine]) -> str:
    """한 줄이면 그대로, 여러 줄이면 첫 줄 + "외 N 가지". 길게 늘어놓으면 어르신이 따라가기 어렵다."""
    head = _item(lines[0])
    return head if len(lines) == 1 else f"{head} 외 {ko_count(len(lines) - 1)} 가지"


def _arrive(lines: list[SpokenLine], today: date) -> str:
    """가장 늦게 오는 줄 기준. 줄마다 도착일이 다르면 "늦어도" 를 붙인다."""
    days = [x.delivery_days for x in lines]
    _, spoken = arrive(today, max(days))
    return spoken if len(set(days)) == 1 else f"늦어도 {spoken}"


def prepared(lines: list[SpokenLine], total: int, today: date) -> str:
    """"새치 염색약 1회분 흑갈색 두 개, 배송비 포함 만 칠천팔백 원이에요. 내일 도착해요. 주문할까요?" """
    return f"{_items(lines)}, 배송비 포함 {read_won(total)}이에요. {_arrive(lines, today)} 도착해요. 주문할까요?"


def confirmed(lines: list[SpokenLine], total: int, today: date) -> tuple[str, str]:
    """(요약, 도착 읽는 말). 요약 예: "주문했어요. 새치 염색약 1회분 흑갈색 두 개, 모두 만 칠천팔백 원이에요. 내일 도착해요." """
    when = _arrive(lines, today)
    return f"주문했어요. {_items(lines)}, 모두 {read_won(total)}이에요. {when} 도착해요.", when


def cancelled(name: str, refund: int) -> str:
    """"새치 염색약 1회분 주문을 취소했어요. 만 칠천팔백 원은 돌려 드려요." """
    return f"{name} 주문을 취소했어요. {read_won(refund)}은 돌려 드려요."
