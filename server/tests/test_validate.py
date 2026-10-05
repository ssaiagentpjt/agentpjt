import json
import shutil
from pathlib import Path

import pytest

from app.validate import check

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def data(tmp_path):
    shutil.copytree(FIXTURES, tmp_path / "data")
    return tmp_path / "data"


def edit(path: Path, fn):
    items = json.loads(path.read_text(encoding="utf-8"))
    fn(items)
    path.write_text(json.dumps(items, ensure_ascii=False), encoding="utf-8")


def test_full_check_passes_with_warnings(data):
    errors, warnings = check(data, None)
    assert errors == []
    assert any("비어 있는 소분류" in w for w in warnings)  # 진행 중 상태는 경고로만


def test_partial_and_strict_treat_empty_subs_as_errors(data):
    errors, _ = check(data, {"medical"})
    assert errors and all("medical" in e or "의료" in e for e in errors)
    errors, _ = check(data, None, strict=True)
    assert any("비어 있는 소분류" in e for e in errors)


def test_partial_check_ignores_broken_other_files(data):
    (data / "products" / "food.json").write_text("[{\"broken\": true}]", encoding="utf-8")
    errors, _ = check(data, {"silver"})
    assert not any("food" in e for e in errors)


def test_id_prefix_and_brand_pool(data):
    def bad(items):
        items[0]["productId"] = "p09001"  # silver 접두어는 07
        items[1]["brand"] = "늘편한"  # silver 브랜드 풀 밖
    edit(data / "products" / "silver.json", bad)
    errors, _ = check(data, None)
    assert any("p07 로 시작해야" in e for e in errors)
    assert any("브랜드 풀에 없다" in e for e in errors)


def test_fixed_demo_products_required(data):
    edit(data / "products" / "food.json", lambda items: items[0].update(productName="다른 쌀"))
    errors, _ = check(data, None)
    assert any("시연 고정 상품 p01001" in e for e in errors)


def test_unknown_main_option(data):
    errors, _ = check(data, {"cars"})
    assert errors and "cars" in errors[0]
