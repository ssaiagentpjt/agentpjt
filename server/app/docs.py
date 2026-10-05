"""Swagger(/docs)·ReDoc(/redoc)에 보이는 문서 문구. 라우트 코드(main.py)를 읽기 쉽게 두려고 따로 모았다."""

from .models import OrderErrorOut

SUMMARY = "노인용 음성 쇼핑 에이전트 '손주야'가 쓰는 목업 쇼핑 API"

DESCRIPTION = """
어르신이 말로 원하는 것을 이야기하면, 앱 안의 온디바이스 LLM(Gemma)이 이 API를 **도구처럼 불러** 상품을 찾고 주문합니다.
상품 2,974개는 모두 **가상 데이터**입니다(실제 브랜드·가격과 무관). 주문·결제도 목업입니다.

## 인증
상태 확인(`/health`)과 이미지(`/static`)를 뺀 모든 요청에 `X-API-Key` 헤더가 필요합니다.
오른쪽 위 **Authorize** 버튼에 키를 한 번 넣으면 이 화면의 모든 요청에 붙습니다.
키는 브라우저에 저장되니, 공용 PC에서 시연한 뒤에는 **Logout** 을 눌러 주세요.

## 응답 두 가지
| view | 쓰임 | 크기 |
|---|---|---|
| `compact` (검색 기본) | 모델에게 넘기는 요약. 판단에 필요한 값만 | 한 건 약 400바이트 |
| `full` (상세 기본) | 화면 표시용 전체 정보(분류 3단계, 배송, 리뷰 요약, 옵션 재고 …) | 한 건 약 2KB |

온디바이스 모델은 입력이 길수록 느려지므로, 검색은 compact 5건이 기본입니다.

## 서버가 계산해 주는 지표
- **가격대 등급** (`tier`, `priceTier`): 같은 중분류 안 가격 백분위로 *싼 편 / 보통 가격 / 비싼 편*
- **순위** (`rankInMid` 등): 최근 30일 판매수 기준. 소분류·중분류·대분류·전체 4가지
- **배지**: 베스트(중분류 3위 안), 할인(10% 이상), 로켓배송, 무료배송, 재구매 많음(40% 이상), 품절 임박
- **읽는 가격** (`priceSpoken`): "만 오천팔백 원"처럼 소리 내어 읽는 말. 모델이 숫자를 잘못 읽지 않게 서버가 만듭니다.

## 옵션 상품 주문 흐름
색상·사이즈·도수·맛 같은 옵션이 있는 상품을 옵션 없이 주문하면 **422** 와 함께 고를 수 있는 값(`choices`)이 옵니다.
앱은 이걸 보고 "어떤 사이즈로 드릴까요? M, L, XL 이 있어요"라고 **되묻고**, 답을 받아 다시 주문합니다.

1. `POST /orders` `{"productId": "p07002", ...}` → **422** `{"missing": ["사이즈"], "choices": {"사이즈": ["M", "L", "XL"]}}`
2. 어르신: "엘로 줘"
3. `POST /orders` `{"productId": "p07002", "options": {"사이즈": "L"}, ...}` → **200** 주문번호

## 바로 해 보기
- 무릎 파스 중 싼 편: `GET /products/search?q=무릎 파스&priceTier=low`
- 손주 선물: `GET /products/search?q=선물&audience=kids&gift=true`
- 많이 팔리는 쌀: `GET /products/search?q=쌀&sort=sales`
- 시연 사용자의 지난 구매: `GET /users/u001/orders`
"""

TAGS = [
    {"name": "상태", "description": "서버가 살아 있는지. 인증 없이 부를 수 있습니다."},
    {"name": "분류", "description": "대/중/소 3단계 분류 트리. 검색의 `main`·`mid`·`sub` 값이 여기 있는 id 입니다."},
    {"name": "상품", "description": "검색과 상세. 검색어는 낱말마다 상품명·태그·분류명·브랜드·옵션값에서 부분 일치로 찾습니다."},
    {"name": "주문", "description": "목업 주문과 구매 이력. 구매 이력은 에이전트의 장기기억(\"지난번에 산 쌀\")에 씁니다."},
]

SWAGGER_UI = {
    "persistAuthorization": True,  # 새로고침해도 Authorize 에 넣은 키를 유지
    "tryItOutEnabled": True,  # Try it out 을 누르지 않아도 바로 시험
    "docExpansion": "list",
    "defaultModelsExpandDepth": 0,  # 아래 스키마 목록은 접어 둔다
}

UNAUTHORIZED = {401: {"description": "`X-API-Key` 가 없거나 틀림", "content": {"application/json": {
    "example": {"detail": "X-API-Key 가 없거나 틀렸다"}}}}}

PRODUCT_NOT_FOUND = {404: {"description": "없는 상품 id", "content": {"application/json": {
    "example": {"detail": "없는 상품: p99999"}}}}}

ORDER_ERRORS = {
    404: {"model": OrderErrorOut, "description": "없는 상품 id", "content": {"application/json": {
        "example": {"detail": {"message": "없는 상품: p99999"}}}}},
    409: {
        "model": OrderErrorOut,
        "description": "상품 또는 고른 옵션이 품절. `choices` 에 지금 고를 수 있는 값이 있습니다.",
        "content": {"application/json": {"example": {"detail": {
            "message": "고른 옵션이 품절이다", "soldOut": ["도수 +2.5"], "choices": {"도수": ["+1.5", "+2.0", "+3.0"]}}}}},
    },
    422: {
        "model": OrderErrorOut,
        "description": "옵션을 고르지 않았거나 없는 값을 고름. **앱은 `choices` 로 되묻습니다.** "
                       "(요청 본문 형식이 틀린 경우의 422 는 FastAPI 기본 형식 `detail: [...]` 으로 옵니다)",
        "content": {"application/json": {"example": {"detail": {
            "message": "옵션을 골라야 한다", "unknownOptions": [], "missing": ["사이즈"], "invalid": {},
            "choices": {"사이즈": ["M", "L", "XL"]}}}}},
    },
}

# 여러 상품 주문: 단건과 같은 오류에 문제가 된 줄(index·productId)이 붙는다
BATCH_ORDER_ERRORS = {
    404: {"model": OrderErrorOut, "description": "어떤 줄의 상품 id 가 없음. 아무것도 주문하지 않았습니다.",
          "content": {"application/json": {"example": {"detail": {"message": "없는 상품: p99999", "index": 1, "productId": "p99999"}}}}},
    409: {"model": OrderErrorOut, "description": "어떤 줄의 상품·옵션이 품절. 아무것도 주문하지 않았습니다.",
          "content": {"application/json": {"example": {"detail": {
              "message": "품절된 상품이다", "index": 0, "productId": "p05010"}}}}},
    422: {"model": OrderErrorOut,
          "description": "어떤 줄의 옵션이 빠졌거나 없는 값. 아무것도 주문하지 않았습니다. **앱은 `choices` 로 되묻습니다.**",
          "content": {"application/json": {"example": {"detail": {
              "message": "옵션을 골라야 한다", "missing": ["사이즈"], "choices": {"사이즈": ["M", "L", "XL"]},
              "index": 1, "productId": "p07002"}}}}},
}

SORT_HELP = (
    "정렬. `relevance` 검색어 일치 점수순(기본) · `sales` 최근 30일 판매순 · `rating` 평점순 · "
    "`price_asc` 싼 순 · `price_desc` 비싼 순 · `discount` 할인율 큰 순 · "
    "`unit_price` 단위 가격 싼 순(1매당·100g당 등, '양 많은 걸로')"
)
