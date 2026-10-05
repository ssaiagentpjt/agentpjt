"""시드 입력값에서 계산하는 지표. 모두 순수 함수다.

AI(앱의 Gemma)가 "싼 거", "많이 팔리는 거", "양 많은 거"를 판단할 때 쓰는 상대 지표들이라
생성 데이터에 직접 쓰게 하지 않고 여기서 일관되게 만든다.
"""

from dataclasses import dataclass

from .models import PriceTierLevel, ProductSeed

PER_100 = {"g", "ml"}  # 1g 당은 숫자가 너무 작아 100g·100ml 당으로 준다

TIER_LABEL: dict[PriceTierLevel, str] = {"low": "싼 편", "mid": "보통 가격", "high": "비싼 편"}

BEST_RANK = 3  # 중분류 안 순위
MIN_GROUP = 5  # 가격 백분위를 낼 최소 상품 수. 중분류가 이보다 작으면 대분류 기준으로 낸다
DISCOUNT_BADGE = 10
REPURCHASE_BADGE = 40


@dataclass(frozen=True)
class Metrics:
    discount_rate: int
    unit_price: int
    unit_per: str
    price_percentile: int
    price_tier: PriceTierLevel
    rank_in_sub: int
    rank_in_mid: int
    rank_in_main: int
    rank_overall: int
    badges: list[str]


def discount_rate(price: int, original: int | None) -> int:
    if not original or original <= price:
        return 0
    return round((original - price) / original * 100)


def unit_price(price: int, quantity: float, unit: str) -> tuple[int, str]:
    """(값, 기준). 예: (1317, "1매"), (412, "100g"), (3490, "1kg")"""
    if unit in PER_100:
        return round(price / quantity * 100), f"100{unit}"
    return round(price / quantity), f"1{unit}"  # 매·개·kg·L … 은 1단위당


def percentile_of(value: int, values: list[int]) -> int:
    """values 안에서 value 보다 싼 것의 비율(0~100). 하나뿐이면 50."""
    if len(values) <= 1:
        return 50
    cheaper = sum(1 for v in values if v < value)
    return round(cheaper / (len(values) - 1) * 100)


def tier_of(percentile: int) -> PriceTierLevel:
    if percentile < 34:
        return "low"
    if percentile < 67:
        return "mid"
    return "high"


def _sales_order(ps: list[ProductSeed]) -> list[ProductSeed]:
    return sorted(ps, key=lambda p: (-p.stats.salesCount30d, -p.stats.rating, p.productId))


def _ranks(groups: dict[tuple, list[ProductSeed]]) -> dict[str, int]:
    out = {}
    for items in groups.values():
        for i, p in enumerate(_sales_order(items)):
            out[p.productId] = i + 1
    return out


def _group(products: list[ProductSeed], key) -> dict[tuple, list[ProductSeed]]:
    groups: dict[tuple, list[ProductSeed]] = {}
    for p in products:
        groups.setdefault(key(p), []).append(p)
    return groups


def compute(products: list[ProductSeed]) -> dict[str, Metrics]:
    by_main = _group(products, lambda p: (p.category.main,))
    by_mid = _group(products, lambda p: (p.category.main, p.category.mid))
    by_sub = _group(products, lambda p: (p.category.main, p.category.mid, p.category.sub))

    rank_sub, rank_mid, rank_main = _ranks(by_sub), _ranks(by_mid), _ranks(by_main)
    rank_overall = {p.productId: i + 1 for i, p in enumerate(_sales_order(products))}

    # 가격 백분위: 같은 중분류 안 비교. 대분류는 1만 원 리모컨과 50만 원 안마의자가 섞여 등급이 무의미해진다
    pct: dict[str, int] = {}
    for (main, _mid), items in by_mid.items():
        pool = items if len(items) >= MIN_GROUP else by_main[(main,)]
        prices = [p.pricing.price for p in pool]
        for p in items:
            pct[p.productId] = percentile_of(p.pricing.price, prices)

    out: dict[str, Metrics] = {}
    for p in products:
        pid = p.productId
        d = discount_rate(p.pricing.price, p.pricing.originalPrice)
        up, per = unit_price(p.pricing.price, p.spec.quantity, p.spec.unit)
        badges = []
        if rank_mid[pid] <= BEST_RANK:
            badges.append("베스트")
        if d >= DISCOUNT_BADGE:
            badges.append("할인")
        if p.delivery.isRocket:
            badges.append("로켓배송")
        if p.delivery.isFreeShipping:
            badges.append("무료배송")
        if p.stats.repurchaseRate >= REPURCHASE_BADGE:
            badges.append("재구매 많음")
        if p.stock.status == "low":
            badges.append("품절 임박")
        out[pid] = Metrics(
            discount_rate=d,
            unit_price=up,
            unit_per=per,
            price_percentile=pct[pid],
            price_tier=tier_of(pct[pid]),
            rank_in_sub=rank_sub[pid],
            rank_in_mid=rank_mid[pid],
            rank_in_main=rank_main[pid],
            rank_overall=rank_overall[pid],
            badges=badges,
        )
    return out