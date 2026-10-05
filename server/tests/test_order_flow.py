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


# ---- 재고 (G-02) ----


def stock(client, pid):
    return client.get(f"/products/{pid}", headers=KEY).json()["stock"]


def test_order_reduces_stock(client):
    before = stock(client, "p03003")["quantity"]
    assert order(client, "p03003", qty=3).status_code == 200
    assert stock(client, "p03003")["quantity"] == before - 3


def test_stock_status_follows_quantity(client):
    # p03007 은 재고 8개(low). 8개를 다 사면 sold_out 이 되고 더 주문할 수 없다
    assert stock(client, "p03007") == {"status": "low", "quantity": 8}
    assert order(client, "p03007", qty=8).status_code == 200
    assert stock(client, "p03007") == {"status": "sold_out", "quantity": 0}
    r = order(client, "p03007")
    assert (r.status_code, r.json()["detail"]["code"]) == (409, "OUT_OF_STOCK")


def test_quantity_over_stock_is_rejected(client):
    r = order(client, "p03007", qty=9)
    d = r.json()["detail"]
    assert (r.status_code, d["code"], d["available"]) == (409, "OUT_OF_STOCK", 8)
    assert stock(client, "p03007")["quantity"] == 8


def test_batch_sums_same_product_against_stock(client):
    r = client.post("/orders/batch", headers=KEY, json={"userId": "u1", "items": [
        {"productId": "p03007", "quantity": 5}, {"productId": "p03007", "quantity": 4}]})
    d = r.json()["detail"]
    assert (r.status_code, d["code"], d["available"], d["index"]) == (409, "OUT_OF_STOCK", 8, 0)
    assert stock(client, "p03007")["quantity"] == 8


def test_batch_reduces_stock_per_line(client):
    before = stock(client, "p03003")["quantity"]
    r = client.post("/orders/batch", headers=KEY, json={"userId": "u1", "items": [
        {"productId": "p03003", "quantity": 2}, {"productId": "p03003", "quantity": 1}]})
    assert r.status_code == 200
    assert stock(client, "p03003")["quantity"] == before - 3


def test_stock_survives_restart(db_path, clock):
    c1 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key="k", clock=clock))
    assert order(c1, "p03007", qty=5).status_code == 200
    c2 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key="k", clock=clock))
    assert stock(c2, "p03007") == {"status": "low", "quantity": 3}
