import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app import db
from app.main import create_app
from app.models import ProductSeed

FIXTURES = Path(__file__).parent / "fixtures"
KEY = {"X-API-Key": "k"}


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(data_dir=FIXTURES, db_path=str(tmp_path / "shop.db"), api_key="k"))


def order(client, pid, qty=1, **options):
    return client.post("/orders", json={"userId": "u1", "productId": pid, "quantity": qty, "options": options}, headers=KEY)


def test_seed_schema_limits():
    base = db.load_products(FIXTURES)[0].model_dump()
    with pytest.raises(ValidationError):  # 축 3개는 안 됨
        ProductSeed(**{**base, "options": [{"name": f"축{i}", "values": [{"value": "a"}, {"value": "b"}]} for i in range(3)]})
    with pytest.raises(ValidationError):  # 값 1개는 옵션이 아님
        ProductSeed(**{**base, "options": [{"name": "색상", "values": [{"value": "검정"}]}]})


def test_full_view_options_inherit_product_stock(client):
    p = client.get("/products/p07002", headers=KEY).json()
    assert p["options"] == [{"name": "사이즈", "values": [
        {"value": "M", "priceAdd": 0, "stock": "in_stock"},
        {"value": "L", "priceAdd": 0, "stock": "in_stock"},
        {"value": "XL", "priceAdd": 2000, "stock": "low"},
    ]}]


def test_missing_option_returns_choices_for_follow_up_question(client):
    r = order(client, "p07002")
    assert r.status_code == 422
    d = r.json()["detail"]
    assert d["missing"] == ["사이즈"] and d["choices"] == {"사이즈": ["M", "L", "XL"]}


def test_invalid_and_unknown_options(client):
    d = order(client, "p07002", 사이즈="XXL").json()["detail"]
    assert d["invalid"] == {"사이즈": "XXL"} and d["choices"]["사이즈"] == ["M", "L", "XL"]
    d = order(client, "p07002", 사이즈="L", 색상="빨강").json()["detail"]
    assert d["unknownOptions"] == ["색상"]
    # 옵션 없는 상품에 옵션을 주어도 422
    assert order(client, "p03005", 사이즈="L").status_code == 422


def test_sold_out_option_is_409(client):
    r = order(client, "p03006", 도수="+2.5")
    assert r.status_code == 409
    assert "+2.5" not in r.json()["detail"]["choices"]["도수"]


def test_price_add_is_charged_and_stored(client):
    r = order(client, "p07002", qty=2, 사이즈="XL")
    assert r.status_code == 200
    body = r.json()
    base = client.get("/products/p07002", headers=KEY).json()
    expected = (base["pricing"]["price"] + 2000) * 2 + base["delivery"]["shippingFee"]
    assert body["totalPrice"] == expected and body["options"] == {"사이즈": "XL"}
    history = client.get("/users/u1/orders", headers=KEY).json()
    assert history[0]["options"] == {"사이즈": "XL"}


def test_sold_out_product_is_409(client):
    assert order(client, "p07003").status_code == 409


def test_old_db_gets_options_column(tmp_path):
    path = tmp_path / "old.db"
    conn = sqlite3.connect(path)
    conn.execute("create table orders (orderId text primary key, userId text not null, productId text not null,"
                 " quantity integer not null, totalPrice integer not null, orderedAt text not null)")
    conn.execute("insert into orders values ('M-1', 'u7', 'p03005', 1, 15800, '2026-01-01')")
    conn.commit()
    conn.close()
    c = TestClient(create_app(data_dir=FIXTURES, db_path=str(path), api_key=None))
    assert c.get("/users/u7/orders").json()[0]["options"] == {}
