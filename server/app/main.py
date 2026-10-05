"""목업 쇼핑 API. 실행: uvicorn --factory app.main:app_from_env

환경변수
- SHOP_API_KEY: 설정하면 /health·/static 을 뺀 모든 요청에 X-API-Key 헤더를 요구한다. 비우면 개발용으로 열어 둔다.
- SHOP_DB_PATH: SQLite 파일 (기본 ./shop.db). 상품 테이블은 시작할 때마다 시드로 다시 만들고, 주문만 남는다.
- SHOP_DATA_DIR: categories.json 등 시드가 있는 폴더 (기본 server/data)
- SHOP_PUBLIC_BASE_URL: 이미지 URL 앞에 붙일 공개 주소. 비우면 요청이 들어온 주소를 쓴다.
"""

import logging
import os
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.staticfiles import StaticFiles

from .models import CategoryOut, OrderIn, OrderOut, ProductCompact, ProductOut, SearchOut
from .search import Audience, Sort, Tier
from .store import DEFAULT_DATA_DIR, STATIC_DIR, OrderError, Store

VERSION = "0.4.0"
View = Literal["compact", "full"]
log = logging.getLogger("shop")


def create_app(
    data_dir: Path = DEFAULT_DATA_DIR,
    db_path: str = "shop.db",
    api_key: str | None = None,
    public_base_url: str | None = None,
) -> FastAPI:
    store = Store(data_dir, db_path)
    app = FastAPI(title="손주야 목업 상품 API", version=VERSION)
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    if not api_key:
        log.warning("SHOP_API_KEY 가 비어 있어 인증 없이 열려 있다 (개발용)")

    def require_key(x_api_key: Annotated[str | None, Header()] = None) -> None:
        if api_key and x_api_key != api_key:
            raise HTTPException(status_code=401, detail="X-API-Key 가 없거나 틀렸다")

    def base(request: Request) -> str:
        return (public_base_url or str(request.base_url)).rstrip("/")

    @app.get("/health")
    def health() -> dict:
        return {"ok": True, "version": VERSION, "products": store.product_count()}

    @app.get("/categories", dependencies=[Depends(require_key)])
    def categories(request: Request) -> list[CategoryOut]:
        return store.categories(base(request))

    @app.get("/products/search", dependencies=[Depends(require_key)])
    def search_products(
        request: Request,
        q: str = "",
        main: str | None = None,
        mid: str | None = None,
        sub: str | None = None,
        minPrice: Annotated[int | None, Query(ge=0)] = None,
        maxPrice: Annotated[int | None, Query(ge=0)] = None,
        priceTier: Tier | None = None,
        audience: Audience | None = None,
        gift: bool | None = None,
        includeSoldOut: bool = False,
        sort: Sort = "relevance",
        # 모델이 고르는 것은 최대 3개라 기본 5개. compact 가 기본인 것도 온디바이스 모델의 입력 토큰을 줄이려는 것
        limit: Annotated[int, Query(ge=1, le=30)] = 5,
        view: View = "compact",
    ) -> SearchOut:
        if main and not store.main_exists(main):
            raise HTTPException(status_code=400, detail=f"없는 대분류: {main}")
        total, found = store.search(
            base(request), q=q, main=main, mid=mid, sub=sub, min_price=minPrice, max_price=maxPrice,
            price_tier=priceTier, audience=audience, gift=gift, include_sold_out=includeSoldOut, sort=sort, limit=limit,
            compact=view == "compact",
        )
        return SearchOut(query=q, total=total, products=found)

    @app.get("/products/{product_id}", dependencies=[Depends(require_key)])
    def product(product_id: str, request: Request, view: View = "full") -> ProductOut | ProductCompact:
        p = store.product(product_id, base(request), compact=view == "compact")
        if p is None:
            raise HTTPException(status_code=404, detail=f"없는 상품: {product_id}")
        return p

    @app.post("/orders", dependencies=[Depends(require_key)])
    def place_order(body: OrderIn) -> OrderOut:
        try:
            order_id, name, total, at = store.place_order(body.userId, body.productId, body.quantity, body.options)
        except OrderError as e:
            # 422(옵션 누락·잘못된 값)·409(품절) 본문의 choices 로 앱 에이전트가 되묻는다
            raise HTTPException(status_code=e.status, detail=e.detail) from e
        return OrderOut(orderId=order_id, userId=body.userId, productId=body.productId, productName=name,
                        quantity=body.quantity, options=body.options, totalPrice=total, orderedAt=at)

    @app.get("/users/{user_id}/orders", dependencies=[Depends(require_key)])
    def orders(user_id: str, limit: Annotated[int, Query(ge=1, le=100)] = 20) -> list[OrderOut]:
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
