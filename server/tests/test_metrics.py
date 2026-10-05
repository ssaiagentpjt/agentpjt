from pathlib import Path

from app.db import load_products
from app.metrics import compute, discount_rate, percentile_of, tier_of, unit_price

PRODUCTS = load_products(Path(__file__).parent / "fixtures")
M = compute(PRODUCTS)


def by(main, mid=None):
    return [p for p in PRODUCTS if p.category.main == main and (mid is None or p.category.mid == mid)]


def test_discount_rate():
    assert discount_rate(9_900, 12_000) == 18
    assert discount_rate(9_900, None) == 0
    assert discount_rate(9_900, 9_000) == 0  # 원가가 더 싸면 할인 아님


def test_unit_price_bases():
    assert unit_price(15_800, 12, "매") == (1317, "1매")
    assert unit_price(4_120, 1000, "g") == (412, "100g")
    assert unit_price(34_900, 10, "kg") == (3490, "1kg")


def test_percentile_and_tier():
    prices = [1000, 2000, 3000, 4000, 5000]
    assert [percentile_of(p, prices) for p in prices] == [0, 25, 50, 75, 100]
    assert percentile_of(1000, [1000]) == 50
    assert (tier_of(0), tier_of(50), tier_of(90)) == ("low", "mid", "high")


def test_small_mid_falls_back_to_main_for_percentile():
    # medical > pain-relief 는 4개(<5)라 medical 전체 10개와 비교한다
    medical_prices = [p.pricing.price for p in by("medical")]
    cool = next(p for p in PRODUCTS if p.productId == "p03002")
    assert M["p03002"].price_percentile == percentile_of(cool.pricing.price, medical_prices)


def test_four_ranks_follow_30d_sales():
    pain = sorted(by("medical", "pain-relief"), key=lambda p: -p.stats.salesCount30d)
    assert [M[p.productId].rank_in_mid for p in pain] == [1, 2, 3, 4]
    assert sorted(M[p.productId].rank_in_main for p in by("medical")) == list(range(1, 11))
    assert sorted(x.rank_overall for x in M.values()) == list(range(1, len(PRODUCTS) + 1))
    assert M["p01001"].rank_in_sub == 1  # 쌀 소분류에 하나뿐


def test_badges():
    assert {"베스트", "할인", "로켓배송"} <= set(M["p03005"].badges)  # 무릎 핫팩 파스: 중분류 1위, 12% 할인
    assert "품절 임박" in M["p03007"].badges
    assert "무료배송" in M["p03004"].badges and "로켓배송" not in M["p03004"].badges
    assert "재구매 많음" in M["p03001"].badges  # 보청기 건전지, 재구매율 63%
