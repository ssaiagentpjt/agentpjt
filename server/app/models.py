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
    value: int = Field(description="단위당 가격(원)", examples=[1317])
    per: str = Field(description="기준 단위. 셀 수 있는 것은 1단위당, g·ml 은 100g·100ml 당", examples=["1매", "100g", "1kg"])


class PriceTier(BaseModel):
    level: PriceTierLevel = Field(description="low 싼 편 · mid 보통 · high 비싼 편")
    label: str = Field(description="읽기 좋은 이름", examples=["보통 가격"])
    percentile: int = Field(description="같은 중분류 안 가격 백분위(0 = 가장 쌈, 100 = 가장 비쌈). 중분류 상품이 5개 미만이면 대분류 기준")


class PricingOut(BaseModel):
    price: int = Field(description="판매가(원)")
    originalPrice: int | None = Field(description="할인 전 가격(원). 할인이 없으면 null")
    discountRate: int = Field(description="할인율(%). 서버 계산")
    unitPrice: UnitPrice
    priceTier: PriceTier


class DeliveryOut(DeliverySeed):
    arriveLabel: str = Field(description="화면 표기 도착일", examples=["내일 10월 6일(화)"])
    arriveSpoken: str = Field(description="읽어 줄 때 쓰는 말. \"{arriveSpoken} 도착해요\"", examples=["내일", "10월 8일 목요일에"])


class StatsOut(StatsSeed):
    rankInSub: int = Field(description="소분류 안 30일 판매 순위")
    rankInMid: int = Field(description="중분류 안 30일 판매 순위")
    rankInMain: int = Field(description="대분류 안 30일 판매 순위")
    rankOverall: int = Field(description="전체 30일 판매 순위")
    badges: list[str] = Field(description="베스트 · 할인 · 로켓배송 · 무료배송 · 재구매 많음 · 품절 임박")


class OptionValueOut(BaseModel):
    value: str = Field(examples=["L"])
    priceAdd: int = Field(description="이 값을 고르면 더하는 금액(원)")
    stock: StockStatus = Field(description="값별 재고. 따로 없으면 상품 재고를 따른다")


class OptionAxisOut(BaseModel):
    name: str = Field(description="옵션 이름", examples=["사이즈"])
    values: list[OptionValueOut]


class ProductOut(BaseModel):
    """상품 전체 정보(view=full)."""

    productId: str
    productName: str
    brand: str = Field(description="가상 브랜드")
    category: CategoryRefOut
    origin: str = Field(description="제조국·원산지")
    audience: Audience = Field(description="senior 어르신용 · general 일반 · kids 아이용")
    isGift: bool = Field(description="선물용으로 알맞은가")
    pricing: PricingOut
    spec: Spec
    delivery: DeliveryOut
    stats: StatsOut
    content: Content
    stock: Stock
    options: list[OptionAxisOut] = Field(description="옵션 축(0~2개). 비어 있으면 옵션 없는 상품")
    productImage: str = Field(description="이미지 URL. 상품 사진이 없으면 대분류 아이콘(SVG)")
    source: str


class OptionBrief(BaseModel):
    name: str = Field(examples=["사이즈"])
    values: list[str] = Field(description="지금 고를 수 있는 값(품절 값은 뺌)", examples=[["M", "L", "XL"]])


class ProductCompact(BaseModel):
    """검색 기본 응답(view=compact). 온디바이스 모델의 입력 토큰을 줄이려고 판단에 필요한 것만 남겼다(한 건 약 400바이트)."""

    id: str = Field(description="상품 id", examples=["p03005"])
    name: str = Field(description="상품명", examples=["무릎 전용 핫팩 파스 12매"])
    brand: str = Field(description="가상 브랜드", examples=["온케어"])
    sub: str = Field(description="소분류 이름", examples=["핫파스"])
    price: int = Field(description="판매가(원)", examples=[15800])
    priceSpoken: str = Field(description="읽는 가격. 모델은 이 말을 그대로 읽는다", examples=["만 오천팔백 원"])
    tier: str = Field(description="가격대 등급(같은 중분류 안 비교)", examples=["보통 가격"])
    discount: int = Field(description="할인율(%)", examples=[12])
    rating: float = Field(description="평점(1~5)", examples=[4.7])
    reviews: int = Field(description="리뷰 수", examples=[2210])
    rankInMid: int = Field(description="중분류 안 30일 판매 순위", examples=[1])
    badges: list[str] = Field(description="배지", examples=[["베스트", "할인", "로켓배송"]])
    arrive: str = Field(description="읽는 도착일", examples=["내일"])
    stock: StockStatus = Field(description="in_stock · low(품절 임박) · sold_out")
    gift: bool = Field(description="선물용으로 알맞은가")
    image: str = Field(description="이미지 URL. 상품 사진이 없으면 대분류 아이콘(SVG)")
    options: list[OptionBrief] = Field(description="옵션이 있으면 주문 때 모두 골라야 한다")


class SubTreeOut(Named):
    productCount: int
    target: int = Field(description="데이터 생성 목표 수")


class MidTreeOut(Named):
    productCount: int
    target: int
    priceRange: tuple[int, int] = Field(description="이 중분류의 가격대 가이드(원)")
    subs: list[SubTreeOut]


class CategoryOut(Named):
    iconUrl: str = Field(description="대분류 아이콘(SVG)")
    productCount: int
    target: int
    mids: list[MidTreeOut]


class SearchOut(BaseModel):
    query: str = Field(description="받은 검색어")
    total: int = Field(description="조건에 맞는 전체 개수(limit 와 무관)")
    products: list[ProductCompact] | list[ProductOut] = Field(description="view=compact 면 ProductCompact, full 이면 ProductOut")


class HealthOut(BaseModel):
    ok: bool
    version: str = Field(examples=["0.4.1"])
    products: int = Field(description="적재된 상품 수", examples=[2974])


class OrderIn(BaseModel):
    model_config = ConfigDict(json_schema_extra={"examples": [
        {"userId": "u001", "productId": "p07002", "quantity": 1, "options": {"사이즈": "L"}},
        {"userId": "u001", "productId": "p01001", "quantity": 1},
    ]})

    userId: str = Field(min_length=1, max_length=40, description="사용자 id. 시연 사용자는 u001")
    productId: str = Field(description="상품 id")
    quantity: int = Field(ge=1, le=9, description="수량(1~9)")
    options: dict[str, str] = Field(default_factory=dict, description="고른 옵션 {옵션 이름: 값}. 옵션 없는 상품이면 비운다")


class OrderOut(BaseModel):
    orderId: str = Field(description="목업 주문번호", examples=["M-20261005-5080"])
    userId: str
    productId: str
    productName: str
    quantity: int
    options: dict[str, str] = Field(description="고른 옵션")
    totalPrice: int = Field(description="합계(원) = (가격 + 옵션 추가 금액) × 수량 + 배송비")
    orderedAt: str = Field(description="주문 시각(KST, ISO 8601)")


class OrderErrorDetail(BaseModel):
    """주문 실패(422 옵션·409 품절·404) 본문의 detail. 앱 에이전트는 choices 로 되묻는다."""

    message: str
    missing: list[str] = Field(default_factory=list, description="고르지 않은 옵션 이름")
    invalid: dict[str, str] = Field(default_factory=dict, description="없는 값을 고른 옵션 {이름: 보낸 값}")
    unknownOptions: list[str] = Field(default_factory=list, description="이 상품에 없는 옵션 이름")
    soldOut: list[str] = Field(default_factory=list, description="품절인 옵션 값")
    choices: dict[str, list[str]] = Field(default_factory=dict, description="지금 고를 수 있는 값 {옵션 이름: [값…]}")


class OrderErrorOut(BaseModel):
    detail: OrderErrorDetail