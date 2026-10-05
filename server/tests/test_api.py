from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

FIXTURES = Path(__file__).parent / "fixtures"
KEY = {"X-API-Key": "test-key"}


@pytest.fixture
def client(tmp_path):
    app = create_app(data_dir=FIXTURES, db_path=str(tmp_path / "shop.db"), api_key="test-key", public_base_url="https://shop.example")
    return TestClient(app)


def test_health_needs_no_key(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["products"] == 28


def test_key_required(client):
    assert client.get("/products/search", params={"q": "파스"}).status_code == 401
    assert client.get("/products/search", params={"q": "파스"}, headers={"X-API-Key": "wrong"}).status_code == 401


def test_nested_product_shape(client):
    p = client.get("/products/p03005", headers=KEY).json()
    assert p["category"] == {
        "main": {"id": "medical", "name": "의료·건강기기"},
        "mid": {"id": "pain-relief", "name": "파스·외용"},
        "sub": {"id": "hot-patch", "name": "핫파스"},
    }
    assert (p["origin"], p["audience"], p["isGift"]) == ("국산", "senior", False)
    assert p["pricing"]["discountRate"] == 12
    assert p["pricing"]["unitPrice"] == {"value": 1317, "per": "1매"}
    assert set(p["pricing"]["priceTier"]) == {"level", "label", "percentile"}
    assert p["delivery"]["arriveSpoken"] == "내일"
    s = p["stats"]
    # 중분류(파스·외용) 1위지만 대분류(의료) 안에서는 인공눈물·보청기 건전지가 더 팔려 3위
    assert (s["rankInMid"], s["rankInMain"]) == (1, 3) and "베스트" in s["badges"]
    assert {"rankInSub", "rankOverall"} <= set(s)
    assert p["options"] == []
    assert p["spec"]["quantity"] == 12 and isinstance(p["spec"]["quantity"], int)
    assert p["productImage"] == "https://shop.example/static/icons/medical.svg"


def test_search_params(client):
    body = client.get("/products/search", params={"q": "파스", "maxPrice": 20000, "sort": "sales", "view": "full"}, headers=KEY).json()
    sales = [p["stats"]["salesCount30d"] for p in body["products"]]
    assert body["total"] > 0 and sales == sorted(sales, reverse=True)
    low = client.get("/products/search", params={"main": "medical", "priceTier": "low"}, headers=KEY).json()
    assert low["products"] and all(p["tier"] == "싼 편" for p in low["products"])
    gifts = client.get("/products/search", params={"gift": "true", "audience": "senior", "view": "full"}, headers=KEY).json()
    assert gifts["products"] and all(p["isGift"] and p["audience"] == "senior" for p in gifts["products"])
    assert client.get("/products/search", params={"priceTier": "cheap"}, headers=KEY).status_code == 422


def test_search_defaults_to_small_compact_results(client):
    body = client.get("/products/search", params={"q": "무릎 파스"}, headers=KEY).json()
    assert len(body["products"]) == 5  # 기본 limit 5
    p = body["products"][0]
    assert p == {
        "id": "p03005", "name": "무릎 전용 핫팩 파스 12매", "brand": "온케어", "sub": "핫파스",
        "price": 15800, "priceSpoken": "만 오천팔백 원", "tier": p["tier"], "discount": 12,
        "rating": 4.7, "reviews": 2210, "rankInMid": 1, "badges": ["베스트", "할인", "로켓배송"],
        "arrive": "내일", "stock": "in_stock", "gift": False, "options": [],
    }
    # 온디바이스 모델에 넘기는 크기: 5건 합쳐 2.5KB 이하
    import json
    assert len(json.dumps(body["products"], ensure_ascii=False).encode()) <= 2500


def test_product_view_compact(client):
    p = client.get("/products/p03006", params={"view": "compact"}, headers=KEY).json()
    assert p["options"] == [{"name": "도수", "values": ["+1.5", "+2.0", "+3.0"]}]  # 품절 값(+2.5)은 뺀다


def test_unknown_main_is_400(client):
    assert client.get("/products/search", params={"main": "cars"}, headers=KEY).status_code == 400


def test_product_404(client):
    assert client.get("/products/p99999", headers=KEY).status_code == 404


def test_category_tree(client):
    tree = client.get("/categories", headers=KEY).json()
    assert [m["id"] for m in tree] == ["food", "health-food", "medical", "living", "hygiene", "silver"]
    medical = tree[2]
    assert medical["productCount"] == 10 and medical["target"] > medical["productCount"]
    pain = next(d for d in medical["mids"] if d["id"] == "pain-relief")
    assert pain["productCount"] == 4 and [s["id"] for s in pain["subs"]][:2] == ["cool-patch", "hot-patch"]
    assert client.get("/static/icons/medical.svg").status_code == 200


def test_order_then_history(client):
    seeded = client.get("/users/u001/orders", headers=KEY).json()
    assert len(seeded) == 5 and seeded[0]["productName"] == "햅쌀 10kg" and seeded[0]["options"] == {}

    r = client.post("/orders", json={"userId": "u001", "productId": "p03003", "quantity": 2}, headers=KEY)
    assert r.status_code == 200
    assert r.json()["totalPrice"] == 18_500 * 2 + 3_000

    history = client.get("/users/u001/orders", headers=KEY).json()
    assert history[0]["orderId"] == r.json()["orderId"]


def test_order_validation(client):
    assert client.post("/orders", json={"userId": "u001", "productId": "p03003", "quantity": 12}, headers=KEY).status_code == 422
    assert client.post("/orders", json={"userId": "u001", "productId": "p99999", "quantity": 1}, headers=KEY).status_code == 404


def test_restart_keeps_orders_and_reloads_products(tmp_path):
    db_path = str(tmp_path / "shop.db")
    c1 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key=None))
    oid = c1.post("/orders", json={"userId": "u9", "productId": "p03002", "quantity": 1}).json()["orderId"]
    c2 = TestClient(create_app(data_dir=FIXTURES, db_path=db_path, api_key=None))
    assert c2.get("/users/u9/orders").json()[0]["orderId"] == oid
    assert c2.get("/health").json()["products"] == 28
