from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="module")
def client(tmp_path_factory):
    db = tmp_path_factory.mktemp("docs") / "shop.db"
    return TestClient(create_app(data_dir=FIXTURES, db_path=str(db), api_key="k"))


@pytest.fixture(scope="module")
def spec(client):
    return client.get("/openapi.json").json()


def test_api_key_security_scheme_gives_authorize_button(spec):
    assert spec["components"]["securitySchemes"]["ApiKey"] == {
        "type": "apiKey", "in": "header", "name": "X-API-Key",
        "description": "서버 운영자에게 받은 API 키. 앱은 모든 요청에 이 헤더를 붙입니다.",
    }


def test_security_applies_to_protected_endpoints_only(spec):
    paths = spec["paths"]
    assert "security" not in paths["/health"]["get"]
    for path, method in [("/categories", "get"), ("/products/search", "get"), ("/products/{product_id}", "get"),
                         ("/orders", "post"), ("/users/{user_id}/orders", "get")]:
        assert paths[path][method]["security"] == [{"ApiKey": []}], path


def test_every_endpoint_has_korean_summary_and_tag(spec):
    tags = {t["name"] for t in spec["tags"]}
    for path, methods in spec["paths"].items():
        for method, op in methods.items():
            assert op.get("summary"), f"{method} {path}"
            assert op.get("tags") and set(op["tags"]) <= tags, f"{method} {path}"


def test_order_error_responses_show_choices(spec):
    responses = spec["paths"]["/orders"]["post"]["responses"]
    for code in ("409", "422"):
        example = responses[code]["content"]["application/json"]["example"]
        assert example["detail"]["choices"], code
    assert "401" in responses and "404" in responses


def test_search_parameters_are_described(spec):
    params = {p["name"]: p for p in spec["paths"]["/products/search"]["get"]["parameters"]}
    assert "unit_price" in params["sort"]["description"] and "sales" in params["sort"]["description"]
    assert "중분류" in params["priceTier"]["description"]
    for name in ("q", "main", "maxPrice", "view", "limit"):
        assert params[name].get("description"), name


def test_docs_pages_render(client):
    docs = client.get("/docs")
    assert docs.status_code == 200 and "persistAuthorization" in docs.text
    assert client.get("/redoc").status_code == 200


def test_authorize_header_still_enforced(client):
    assert client.get("/categories").status_code == 401
    assert client.get("/categories", headers={"X-API-Key": "k"}).status_code == 200
