"""목업 쇼핑 API. 실행: uvicorn --factory app.main:app_from_env

환경변수
- SHOP_API_KEY: 설정하면 /health·/static 을 뺀 모든 요청에 X-API-Key 헤더를 요구한다. 비우면 개발용으로 열어 둔다.
- SHOP_DB_PATH: SQLite 파일 (기본 ./shop.db). 상품 테이블은 시작할 때마다 시드로 다시 만들고, 주문만 남는다.
- SHOP_DATA_DIR: categories.json 등 시드가 있는 폴더 (기본 server/data)
- SHOP_PUBLIC_BASE_URL: 이미지 URL 앞에 붙일 공개 주소. 비우면 요청이 들어온 주소를 쓴다.

문서: /docs(Swagger), /redoc, /openapi.json. 문구는 app/docs.py 에 있다.
"""

import logging
import os
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Security
from fastapi import Path as PathParam
from fastapi.security import APIKeyHeader
from fastapi.staticfiles import StaticFiles

from . import docs
from .models import CategoryOut, HealthOut, OrderIn, OrderOut, ProductCompact, ProductOut, SearchOut
from .search import Audience, Sort, Tier
from .store import DEFAULT_DATA_DIR, STATIC_DIR, OrderError, Store

VERSION = "0.4.1"
View = Literal["compact", "full"]
log = logging.getLogger("shop")

# Swagger 에 Authorize 버튼을 만든다. 키가 비어 있으면 개발용으로 열어 두려고 auto_error 를 끄고 직접 비교한다
api_key_header = APIKeyHeader(
    name="X-API-Key", scheme_name="ApiKey", auto_error=False,
    description="서버 운영자에게 받은 API 키. 앱은 모든 요청에 이 헤더를 붙입니다.",
)


def create_app(
    data_dir: Path = DEFAULT_DATA_DIR,
    db_path: str = "shop.db",
    api_key: str | None = None,
    public_base_url: str | None = None,
) -> FastAPI:
    store = Store(data_dir, db_path)
    app = FastAPI(
        title="손주야 목업 상품 API",
        version=VERSION,
        summary=docs.SUMMARY,
        description=docs.DESCRIPTION,
        openapi_tags=docs.TAGS,
        swagger_ui_parameters=docs.SWAGGER_UI,
    )
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    if not api_key:
        log.warning("SHOP_API_KEY 가 비어 있어 인증 없이 열려 있다 (개발용)")

    def require_key(x_api_key: Annotated[str | None, Security(api_key_header)] = None) -> None:
        if api_key and x_api_key != api_key:
            raise HTTPException(status_code=401, detail="X-API-Key 가 없거나 틀렸다")

    def base(request: Request) -> str:
        return (public_base_url or str(request.base_url)).rstrip("/")

    auth = [Depends(require_key)]

    @app.get("/health", tags=["상태"], summary="서버 상태")
    def health() -> HealthOut:
        """인증 없이 부를 수 있습니다. 적재된 상품 수가 함께 옵니다."""
        return HealthOut(ok=True, version=VERSION, products=store.product_count())

    @app.get("/categories", tags=["분류"], summary="분류 트리", dependencies=auth, responses=docs.UNAUTHORIZED)
    def categories(request: Request) -> list[CategoryOut]:
        """대/중/소 3단계 분류 트리입니다. 단계마다 상품 수(`productCount`)와 목표 수(`target`), 중분류 가격대(`priceRange`)가 있습니다.

        검색의 `main`·`mid`·`sub` 에 여기의 `id` 를 넣습니다.
        """
        return store.categories(base(request))

    @app.get(
        "/products/search", tags=["상품"], summary="상품 검색", dependencies=auth,
        responses={**docs.UNAUTHORIZED, 400: {"description": "없는 대분류 id", "content": {"application/json": {
            "example": {"detail": "없는 대분류: cars"}}}}},
    )
    def search_products(
        request: Request,
        q: Annotated[str, Query(description="검색어. 공백으로 나눈 낱말마다 부분 일치로 찾습니다. 비우면 필터·정렬만 적용합니다.",
                                examples=["무릎 파스"])] = "",
        main: Annotated[str | None, Query(description="대분류 id (`/categories`)", examples=["medical"])] = None,
        mid: Annotated[str | None, Query(description="중분류 id", examples=["pain-relief"])] = None,
        sub: Annotated[str | None, Query(description="소분류 id", examples=["hot-patch"])] = None,
        minPrice: Annotated[int | None, Query(ge=0, description="가격 하한(원)")] = None,
        maxPrice: Annotated[int | None, Query(ge=0, description="가격 상한(원). \"2만 원 안쪽\" → 20000", examples=[20000])] = None,
        priceTier: Annotated[Tier | None, Query(
            description="가격대 등급. 같은 중분류 안 가격 백분위로 나눕니다: `low` 싼 편(하위 1/3) · `mid` 보통 · `high` 비싼 편")] = None,
        audience: Annotated[Audience | None, Query(description="대상. `senior` 어르신용 · `general` 일반 · `kids` 아이용")] = None,
        gift: Annotated[bool | None, Query(description="`true` 면 선물용으로 알맞은 상품만")] = None,
        includeSoldOut: Annotated[bool, Query(description="품절 상품도 포함할지. 기본은 빼고 찾습니다.")] = False,
        sort: Annotated[Sort, Query(description=docs.SORT_HELP)] = "relevance",
        # 모델이 고르는 것은 최대 3개라 기본 5개. compact 가 기본인 것도 온디바이스 모델의 입력 토큰을 줄이려는 것
        limit: Annotated[int, Query(ge=1, le=30, description="돌려줄 개수(1~30). 기본 5")] = 5,
        view: Annotated[View, Query(description="`compact` 모델용 요약(기본) · `full` 화면용 전체 정보")] = "compact",
    ) -> SearchOut:
        """조건에 맞는 상품을 점수순으로 돌려줍니다. `total` 은 조건에 맞는 전체 개수입니다.

        **점수**: 낱말이 상품명에 있으면 3, 태그 2(태그와 정확히 같으면 +1), 소분류명 2, 중분류명·대분류명·브랜드·옵션값 각 1.
        검색어의 **모든 낱말이 맞으면 +4**. 같은 점수면 30일 판매가 많은 순입니다.

        `products` 의 모양은 `view` 에 따라 다릅니다(`ProductCompact` 또는 `ProductOut`).
        """
        if main and not store.main_exists(main):
            raise HTTPException(status_code=400, detail=f"없는 대분류: {main}")
        total, found = store.search(
            base(request), q=q, main=main, mid=mid, sub=sub, min_price=minPrice, max_price=maxPrice,
            price_tier=priceTier, audience=audience, gift=gift, include_sold_out=includeSoldOut, sort=sort, limit=limit,
            compact=view == "compact",
        )
        return SearchOut(query=q, total=total, products=found)

    @app.get("/products/{product_id}", tags=["상품"], summary="상품 상세", dependencies=auth,
             responses={**docs.UNAUTHORIZED, **docs.PRODUCT_NOT_FOUND})
    def product(
        request: Request,
        product_id: Annotated[str, PathParam(description="상품 id (`p` + 5자리)", examples=["p07002"])],
        view: Annotated[View, Query(description="`full` 전체 정보(기본) · `compact` 요약")] = "full",
    ) -> ProductOut | ProductCompact:
        """상품 하나의 전체 정보입니다. 옵션이 있으면 값마다 추가 금액(`priceAdd`)과 재고가 함께 옵니다."""
        p = store.product(product_id, base(request), compact=view == "compact")
        if p is None:
            raise HTTPException(status_code=404, detail=f"없는 상품: {product_id}")
        return p

    @app.post("/orders", tags=["주문"], summary="주문하기 (목업)", dependencies=auth,
              responses={**docs.UNAUTHORIZED, **docs.ORDER_ERRORS})
    def place_order(body: OrderIn) -> OrderOut:
        """목업 주문입니다. 실제 결제는 일어나지 않고, 주문 이력에만 남습니다.

        - 합계 = (가격 + 고른 옵션 값들의 추가 금액) × 수량 + 배송비
        - 옵션이 있는 상품은 **모든 옵션을 골라야** 합니다. 빠지면 **422** 와 고를 수 있는 값(`choices`)이 옵니다.
        - 품절 상품·품절 옵션은 **409**
        """
        try:
            order_id, name, total, at = store.place_order(body.userId, body.productId, body.quantity, body.options)
        except OrderError as e:
            # 422(옵션 누락·잘못된 값)·409(품절) 본문의 choices 로 앱 에이전트가 되묻는다
            raise HTTPException(status_code=e.status, detail=e.detail) from e
        return OrderOut(orderId=order_id, userId=body.userId, productId=body.productId, productName=name,
                        quantity=body.quantity, options=body.options, totalPrice=total, orderedAt=at)

    @app.get("/users/{user_id}/orders", tags=["주문"], summary="구매 이력", dependencies=auth, responses=docs.UNAUTHORIZED)
    def orders(
        user_id: Annotated[str, PathParam(description="사용자 id. 시연 사용자는 `u001`(지난 구매 5건이 미리 들어 있음)",
                                          examples=["u001"])],
        limit: Annotated[int, Query(ge=1, le=100, description="최근 몇 건(1~100). 기본 20")] = 20,
    ) -> list[OrderOut]:
        """최근 주문부터 돌려줍니다. 에이전트가 "지난번에 산 쌀 또 사 줘" 같은 말을 처리할 때 씁니다."""
        return [OrderOut(**r) for r in store.orders_of(user_id, limit)]

    return app


def app_from_env() -> FastAPI:
    """uvicorn --factory app.main:app_from_env 로 띄운다. 모듈을 불러오기만 해도 DB 가 생기지 않게 팩토리로 둔다."""
    return create_app(
        data_dir=Path(os.environ.get("SHOP_DATA_DIR", DEFAULT_DATA_DIR)),
        db_path=os.environ.get("SHOP_DB_PATH", "shop.db"),
        api_key=os.environ.get("SHOP_API_KEY") or None,
        public_base_url=os.environ.get("SHOP_PUBLIC_BASE_URL") or None,
    )
