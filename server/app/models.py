"""데이터 파일(data/*.json, 시드)과 API 응답의 스키마.

시드에는 **입력값만** 둔다. 할인율·단위 가격·가격대 등급·순위·배지·도착일은 서버가 적재할 때 계산한다
(app/metrics.py, app/delivery.py). 생성 데이터끼리 모순이 생기지 않게 하기 위해서다.
필드 이름은 쿠팡 파트너스 검색 응답(productId, productName, productImage, isRocket …)을 따른다.
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

Audience = Literal["senior", "general", "kids"]
Unit = Literal["매", "개", "정", "캡슐", "포", "팩", "봉", "롤", "장", "g", "kg", "ml", "L", "대", "켤레"]
StockStatus = Literal["in_stock", "low", "sold_out"]
PriceTierLevel = Literal["low", "mid", "high"]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ---- 시드 (입력) -----------------------------------------------------------


Slug = Annotated[str, Field(pattern=r"^[a-z][a-z0-9-]*$")]


class SubSeed(Strict):
    id: Slug
    name: str
    target: int = Field(ge=1, le=50)  # 목표 상품 수. 검사기가 달성률을 계산한다


class MidSeed(Strict):
    id: Slug
    name: str
    priceRange: tuple[int, int]  # 가격대 가이드(원). 벗어나면 검사기 경고
    subs: list[SubSeed] = Field(min_length=1)


class MainSeed(Strict):
    id: Slug
    name: str
    # Iconify 의 fluent-emoji-flat 세트 안 아이콘 이름. scripts/fetch_icons.py 가 받는다.
    icon: str
    idPrefix: str = Field(pattern=r"^\d{2}$")  # 상품 id 앞 두 자리. 병렬 생성 때 id 충돌을 막는다
    seniorWeighted: bool  # 어르신이 많이 사는 분류(전체의 약 60%)
    brands: list[str] = Field(min_length=1)  # 이 대분류에서만 쓰는 가상 브랜드 풀
    mids: list[MidSeed] = Field(min_length=1)


class CategoryRef(Strict):
    main: str
    mid: str
    sub: str

class PricingSeed(Strict):
    price: int = Field(ge=100, le=2_000_000)
    originalPrice: int | None = None


class Spec(Strict):
    text: str = Field(min_length=1, max_length=30)
    quantity: int | float = Field(gt=0)  # 정수는 정수 그대로 내보낸다(12, 0.5)
    unit: Unit


class DeliverySeed(Strict):
    isRocket: bool
    isFreeShipping: bool
    shippingFee: int = Field(ge=0, le=10_000)
    deliveryDays: int = Field(ge=1, le=7)


class StatsSeed(Strict):
    salesCount30d: int = Field(ge=0)
    salesCountTotal: int = Field(ge=0)
    rating: float = Field(ge=1.0, le=5.0)
    reviewCount: int = Field(ge=0)
    repurchaseRate: int = Field(ge=0, le=100)


class Content(Strict):
    description: str = Field(min_length=5, max_length=120)
    reviewSummary: str | None = Field(default=None, max_length=120)
    tags: list[str] = Field(min_length=5, max_length=10)


class Stock(Strict):
    status: StockStatus
    quantity: int = Field(ge=0)


class OptionValueSeed(Strict):
    value: str = Field(min_length=1, max_length=20)
    priceAdd: int = Field(default=0, ge=0)  # 기본 가격에 더하는 금액
    stock: StockStatus | None = None  # 없으면 상품 재고를 따른다


class OptionAxisSeed(Strict):
    name: str = Field(min_length=1, max_length=10)  # 사이즈, 색상, 도수, 맛 …
    values: list[OptionValueSeed] = Field(min_length=2, max_length=6)


class ProductSeed(Strict):
    """data/products.json 의 한 줄. GENERATION_GUIDE.md 가 이 스키마를 설명한다."""

    productId: str = Field(pattern=r"^p\d{5}$")  # p + 대분류 idPrefix 2자리 + 일련번호 3자리
    productName: str = Field(min_length=2, max_length=60)
    brand: str = Field(min_length=1, max_length=20)
    category: CategoryRef
    origin: str = Field(min_length=1, max_length=20)  # 제조국·원산지
    audience: Audience  # 누구를 위한 상품인가
    isGift: bool  # 선물용으로 알맞은가
    pricing: PricingSeed
    spec: Spec
    delivery: DeliverySeed
    stats: StatsSeed
    content: Content
    stock: Stock
    # 같은 상품에서 고르기만 하는 차이(색상·사이즈·도수·맛). 규격·가격이 크게 다르면 별개 상품으로 둔다
    options: list[OptionAxisSeed] = Field(default_factory=list, max_length=2)
    productImage: str | None = None
    source: Literal["synthetic", "chamgagyeok"] = "synthetic"


class SeedOrder(Strict):
    """data/seed_orders.json 의 한 줄. 시연 사용자의 지난 구매."""

    orderId: str
    userId: str
    productId: str
    quantity: int = Field(ge=1, le=9)
    orderedAt: str  # ISO 날짜 "2026-09-12"


# ---- 응답 (입력 + 계산) ------------------------------------------------------


class Named(BaseModel):
    id: str
    name: str


class CategoryRefOut(BaseModel):
    main: Named
    mid: Named
    sub: Named

class UnitPrice(BaseModel):
    value: int
    per: str  # "1매", "100g", "1kg"


class PriceTier(BaseModel):
    level: PriceTierLevel
    label: str  # "싼 편" / "보통 가격" / "비싼 편"
    percentile: int  # 같은 카테고리 안 가격 백분위. 0 = 가장 쌈


class PricingOut(BaseModel):
    price: int
    originalPrice: int | None
    discountRate: int
    unitPrice: UnitPrice
    priceTier: PriceTier


class DeliveryOut(DeliverySeed):
    arriveLabel: str
    arriveSpoken: str


class StatsOut(StatsSeed):
    rankInSub: int
    rankInMid: int
    rankInMain: int
    rankOverall: int
    badges: list[str]


class OptionValueOut(BaseModel):
    value: str
    priceAdd: int
    stock: StockStatus


class OptionAxisOut(BaseModel):
    name: str
    values: list[OptionValueOut]


class ProductOut(BaseModel):
    productId: str
    productName: str
    brand: str
    category: CategoryRefOut
    origin: str
    audience: Audience
    isGift: bool
    pricing: PricingOut
    spec: Spec
    delivery: DeliveryOut
    stats: StatsOut
    content: Content
    stock: Stock
    options: list[OptionAxisOut]
    productImage: str
    source: str


class OptionBrief(BaseModel):
    name: str
    values: list[str]  # 품절 값은 뺀다


class ProductCompact(BaseModel):
    """검색 기본 응답. 온디바이스 모델의 입력 토큰을 줄이려고 판단에 필요한 것만 남겼다(한 건 약 400바이트)."""

    id: str
    name: str
    brand: str
    sub: str
    price: int
    priceSpoken: str
    tier: str
    discount: int
    rating: float
    reviews: int
    rankInMid: int
    badges: list[str]
    arrive: str
    stock: StockStatus
    gift: bool
    options: list[OptionBrief]


class SubTreeOut(Named):
    productCount: int
    target: int


class MidTreeOut(Named):
    productCount: int
    target: int
    priceRange: tuple[int, int]
    subs: list[SubTreeOut]


class CategoryOut(Named):
    iconUrl: str
    productCount: int
    target: int
    mids: list[MidTreeOut]

class SearchOut(BaseModel):
    query: str
    total: int
    products: list[ProductCompact] | list[ProductOut]


class OrderIn(BaseModel):
    userId: str = Field(min_length=1, max_length=40)
    productId: str
    quantity: int = Field(ge=1, le=9)
    options: dict[str, str] = Field(default_factory=dict)  # {"사이즈": "L"}


class OrderOut(BaseModel):
    orderId: str
    userId: str
    productId: str
    productName: str
    quantity: int
    options: dict[str, str]
    totalPrice: int
    orderedAt: str
