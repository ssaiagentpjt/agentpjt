"""주문 흐름: 오류 code, 재고, prepare → confirm 2단계, 취소, 요약. 픽스처 상품 28개를 쓴다."""

from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.delivery import KST
from app.main import create_app

FIXTURES = Path(__file__).parent / "fixtures"
KEY = {"X-API-Key": "k"}


class Clock:
    """테스트가 시간을 옮길 수 있는 시계."""

    def __init__(self):
        self.now = datetime(2026, 10, 5, 14, 0, tzinfo=KST)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kw) -> None:
        self.now += timedelta(**kw)


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def db_path(tmp_path):
    return str(tmp_path / "shop.db")


@pytest.fixture
def client(db_path, clock):
    return TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key="k", clock=clock))


def order(client, pid, qty=1, user="u1", **options):
    return client.post("/orders", json={"userId": user, "productId": pid, "quantity": qty, "options": options}, headers=KEY)


# ---- 오류 code ----


def test_order_errors_carry_code(client):
    assert order(client, "p99999").json()["detail"]["code"] == "PRODUCT_NOT_FOUND"
    assert order(client, "p07002").json()["detail"]["code"] == "MISSING_OPTION"
    assert order(client, "p07003").json()["detail"]["code"] == "OUT_OF_STOCK"
    assert order(client, "p03006", 도수="+2.5").json()["detail"]["code"] == "OUT_OF_STOCK"


def test_batch_errors_keep_code_and_line(client):
    r = client.post("/orders/batch", headers=KEY, json={"userId": "u1", "items": [
        {"productId": "p03003", "quantity": 1}, {"productId": "p07002", "quantity": 1}]})
    d = r.json()["detail"]
    assert (r.status_code, d["code"], d["index"], d["productId"]) == (422, "MISSING_OPTION", 1, "p07002")


def test_order_time_comes_from_clock(client, clock):
    assert order(client, "p03003").json()["orderedAt"] == "2026-10-05T14:00:00+09:00"
