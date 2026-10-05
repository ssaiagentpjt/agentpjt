"""주문 흐름: 오류 code, 재고, prepare → confirm 2단계, 취소, 요약. 픽스처 상품 28개를 쓴다."""

import sqlite3
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.delivery import KST
from app.main import create_app
from app.store import Store

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


# ---- 2단계 주문 (G-04) · 중복 방지 (G-01) ----


def prepare(client, *items, user="u1"):
    return client.post("/orders/prepare", headers=KEY, json={"userId": user, "items": [
        {"productId": pid, "quantity": qty, "options": opts} for pid, qty, opts in items]})


def confirm(client, token, user="u1"):
    return client.post("/orders/confirm", headers=KEY, json={"userId": user, "confirmToken": token})


def history(client, user="u1"):
    return client.get(f"/users/{user}/orders", headers=KEY).json()


def test_prepare_alone_creates_no_order(client):
    before = stock(client, "p07002")["quantity"]
    r = prepare(client, ("p07002", 2, {"사이즈": "XL"}), ("p03003", 1, {}))
    body = r.json()
    assert r.status_code == 200 and body["confirmToken"]
    assert body["expiresAt"] == "2026-10-05T14:10:00+09:00"
    assert [(x["productId"], x["unitPrice"], x["totalPrice"]) for x in body["items"]] == [
        ("p07002", 16_900 + 2_000, (16_900 + 2_000) * 2), ("p03003", 18_500, 18_500 + 3_000)]
    assert body["totalPrice"] == 37_800 + 21_500 and body["totalSpoken"] == "오만 구천삼백 원"
    assert history(client) == []
    assert stock(client, "p07002")["quantity"] == before


def test_prepare_reports_bad_line(client):
    r = prepare(client, ("p03003", 1, {}), ("p07002", 1, {}))
    d = r.json()["detail"]
    assert (r.status_code, d["code"], d["index"], d["choices"]["사이즈"]) == (422, "MISSING_OPTION", 1, ["M", "L", "XL"])


def test_confirm_creates_orders_and_reduces_stock(client):
    before = stock(client, "p03003")["quantity"]
    token = prepare(client, ("p03003", 2, {})).json()["confirmToken"]
    r = confirm(client, token)
    body = r.json()
    assert r.status_code == 200 and body["alreadyConfirmed"] is False
    assert [(o["productId"], o["quantity"], o["totalPrice"]) for o in body["orders"]] == [("p03003", 2, 18_500 * 2 + 3_000)]
    assert [o["orderId"] for o in history(client)] == [body["orders"][0]["orderId"]]
    assert stock(client, "p03003")["quantity"] == before - 2


@pytest.mark.parametrize("token", ["", "made-up-token", "q3V0aGVyZQ"])
def test_unknown_token_is_rejected(client, token):
    prepare(client, ("p03003", 1, {}))
    r = confirm(client, token)
    assert (r.status_code, r.json()["detail"]["code"]) == (404, "TOKEN_NOT_FOUND")
    assert history(client) == []


def test_same_token_twice_returns_same_order(client):
    before = stock(client, "p03003")["quantity"]
    token = prepare(client, ("p03003", 1, {})).json()["confirmToken"]
    first, again = confirm(client, token).json(), confirm(client, token).json()
    assert again["alreadyConfirmed"] is True
    assert again["orders"] == first["orders"]
    assert len(history(client)) == 1
    assert stock(client, "p03003")["quantity"] == before - 1


def test_concurrent_confirms_make_one_order(tmp_path, clock):
    store = Store(FIXTURES, str(tmp_path / "c.db"), clock)
    before = store.conn.execute("select stock_qty from products where id = 'p03003'").fetchone()[0]
    token, _, _ = store.prepare("u1", [("p03003", 1, {})])
    with ThreadPoolExecutor(5) as pool:
        results = list(pool.map(lambda _: store.confirm("u1", token), range(5)))
    assert sum(not already for _, already in results) == 1
    assert len({rows[0]["orderId"] for rows, _ in results}) == 1
    assert store.conn.execute("select count(*) from orders where userId = 'u1'").fetchone()[0] == 1
    assert store.conn.execute("select count(*) from stock_ledger").fetchone()[0] == 1
    assert store.conn.execute("select stock_qty from products where id = 'p03003'").fetchone()[0] == before - 1


def test_token_expires_after_ten_minutes(client, clock):
    token = prepare(client, ("p03003", 1, {})).json()["confirmToken"]
    clock.advance(minutes=10)
    r = confirm(client, token)
    assert (r.status_code, r.json()["detail"]["code"]) == (410, "TOKEN_EXPIRED")
    assert history(client) == []


def test_token_just_before_expiry_still_works(client, clock):
    token = prepare(client, ("p03003", 1, {})).json()["confirmToken"]
    clock.advance(minutes=9, seconds=59)
    assert confirm(client, token).status_code == 200


def test_new_prepare_revokes_previous_token(client):
    old = prepare(client, ("p03003", 1, {})).json()["confirmToken"]
    new = prepare(client, ("p03003", 2, {})).json()["confirmToken"]
    r = confirm(client, old)
    assert (r.status_code, r.json()["detail"]["code"]) == (409, "TOKEN_REVOKED")
    assert confirm(client, new).json()["orders"][0]["quantity"] == 2


def test_other_users_token_is_rejected(client):
    token = prepare(client, ("p03003", 1, {}), user="u1").json()["confirmToken"]
    r = confirm(client, token, user="u2")
    assert (r.status_code, r.json()["detail"]["code"]) == (404, "TOKEN_NOT_FOUND")
    assert history(client, "u1") == [] and history(client, "u2") == []


def test_confirm_rechecks_stock(client):
    # 재고 8개 상품을 5개 확인해 둔 사이 다른 사람이 4개를 사 갔다
    token = prepare(client, ("p03007", 5, {})).json()["confirmToken"]
    assert order(client, "p03007", qty=4, user="u2").status_code == 200
    r = confirm(client, token)
    d = r.json()["detail"]
    assert (r.status_code, d["code"], d["available"], d["index"]) == (409, "OUT_OF_STOCK", 4, 0)
    assert history(client) == []


def test_confirm_keeps_prepared_price(client, db_path):
    # prepare 뒤 가격이 바뀌어도 사용자가 듣고 동의한 금액으로 확정한다
    token = prepare(client, ("p03003", 1, {})).json()["confirmToken"]
    with sqlite3.connect(db_path) as conn:
        conn.execute("update products set price = 99000 where id = 'p03003'")
    assert confirm(client, token).json()["orders"][0]["totalPrice"] == 18_500 + 3_000


# ---- 취소 (G-03) ----


def cancel(client, order_id, user="u1"):
    return client.post(f"/orders/{order_id}/cancel", headers=KEY, json={"userId": user})


def test_cancel_restores_stock(client, clock):
    before = stock(client, "p03007")
    token = prepare(client, ("p03007", 8, {})).json()["confirmToken"]
    oid = confirm(client, token).json()["orders"][0]["orderId"]
    assert stock(client, "p03007")["status"] == "sold_out"
    clock.advance(minutes=3)
    r = cancel(client, oid)
    body = r.json()
    assert r.status_code == 200
    assert (body["order"]["status"], body["cancelledAt"], body["refundPrice"]) == ("CANCELLED", "2026-10-05T14:03:00+09:00", 24_000 * 8)
    assert stock(client, "p03007") == before
    assert history(client)[0]["status"] == "CANCELLED"


def test_second_cancel_is_conflict(client):
    oid = order(client, "p03003", qty=2).json()["orderId"]
    before = stock(client, "p03003")["quantity"]
    assert cancel(client, oid).status_code == 200
    r = cancel(client, oid)
    assert (r.status_code, r.json()["detail"]["code"]) == (409, "ALREADY_CANCELLED")
    assert stock(client, "p03003")["quantity"] == before + 2  # 한 번만 돌려놓는다


def test_seed_orders_are_delivered_and_not_cancellable(client):
    seeded = history(client, "u001")
    assert {o["status"] for o in seeded} == {"DELIVERED"}
    r = cancel(client, seeded[0]["orderId"], user="u001")
    assert (r.status_code, r.json()["detail"]["code"]) == (409, "NOT_CANCELLABLE")


def test_cannot_cancel_unknown_or_others_order(client):
    oid = order(client, "p03003", user="u1").json()["orderId"]
    for order_id, user in [("M-20261005-0000", "u1"), (oid, "u2")]:
        r = cancel(client, order_id, user)
        assert (r.status_code, r.json()["detail"]["code"]) == (404, "ORDER_NOT_FOUND")
    assert history(client)[0]["status"] == "CONFIRMED"


def test_cancel_one_line_of_batch(client):
    before = stock(client, "p03003")["quantity"]
    token = prepare(client, ("p03003", 1, {}), ("p07002", 1, {"사이즈": "M"})).json()["confirmToken"]
    first, second = confirm(client, token).json()["orders"]
    assert cancel(client, second["orderId"]).status_code == 200
    statuses = {o["orderId"]: o["status"] for o in history(client)}
    assert statuses == {first["orderId"]: "CONFIRMED", second["orderId"]: "CANCELLED"}
    assert stock(client, "p03003")["quantity"] == before - 1


def test_cancelled_stock_survives_restart(db_path, clock):
    c1 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key="k", clock=clock))
    oid = order(c1, "p03007", qty=5).json()["orderId"]
    assert cancel(c1, oid).status_code == 200
    c2 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key="k", clock=clock))
    assert stock(c2, "p03007") == {"status": "low", "quantity": 8}


def test_order_without_ledger_cancels_without_restock(db_path, clock):
    # 재고 기록이 생기기 전에 들어간 주문(옛 DB)은 줄인 적이 없으므로 취소해도 재고를 늘리지 않는다
    c1 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key="k", clock=clock))
    before = stock(c1, "p03003")["quantity"]
    with sqlite3.connect(db_path) as conn:
        conn.execute("insert into orders (orderId, userId, productId, quantity, totalPrice, orderedAt)"
                     " values ('M-OLD-1', 'u1', 'p03003', 2, 40000, '2026-10-01T10:00:00+09:00')")
    assert cancel(c1, "M-OLD-1").status_code == 200
    assert stock(c1, "p03003")["quantity"] == before


# ---- 요약 (G-05) ----
# 시계는 2026-10-05(월). p07002 무릎 보호대는 2일(모레), p03003 관절 플라스타는 3일(10월 8일 목요일) 걸린다


def test_prepare_summary_reads_item_total_and_arrival(client):
    body = prepare(client, ("p07002", 2, {"사이즈": "XL"})).json()
    assert body["summaryText"] == "무릎 보호대 2개입 XL 두 개, 배송비 포함 삼만 칠천팔백 원이에요. 모레 도착해요. 주문할까요?"


def test_confirm_summary_for_several_lines(client):
    token = prepare(client, ("p07002", 1, {"사이즈": "M"}), ("p03003", 1, {})).json()["confirmToken"]
    body = confirm(client, token).json()
    assert body["arriveSpoken"] == "늦어도 10월 8일 목요일에"
    assert body["summaryText"] == (
        "주문했어요. 무릎 보호대 2개입 M 한 개 외 한 가지, 모두 삼만 팔천사백 원이에요. 늦어도 10월 8일 목요일에 도착해요.")


def test_reconfirm_next_day_gives_same_summary(client, clock):
    token = prepare(client, ("p07002", 1, {"사이즈": "L"})).json()["confirmToken"]
    first = confirm(client, token).json()
    clock.advance(days=1)
    again = confirm(client, token).json()
    assert again["alreadyConfirmed"] and again["summaryText"] == first["summaryText"]
    assert first["arriveSpoken"] == "모레"


def test_cancel_summary(client):
    oid = order(client, "p03003").json()["orderId"]
    assert cancel(client, oid).json()["summaryText"] == "관절 플라스타 34매 주문을 취소했어요. 이만 천오백 원은 돌려 드려요."


def test_confirm_response_stays_small(client):
    # 온디바이스 모델 입력으로 들어가므로 한 줄 주문의 확정 응답은 1KB 안쪽으로 둔다
    token = prepare(client, ("p07002", 1, {"사이즈": "L"})).json()["confirmToken"]
    assert len(confirm(client, token).content) < 1024
