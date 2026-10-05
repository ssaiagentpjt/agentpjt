"""주문 2단계(prepare → confirm)·취소의 Swagger 문구. docs.py 와 같은 이유로 라우트 코드(main.py)와 나눴다."""

from .models import OrderErrorOut


def _error(status: int, description: str, detail: dict) -> dict:
    return {status: {"model": OrderErrorOut, "description": description,
                     "content": {"application/json": {"example": {"detail": detail}}}}}


FLOW = """
## 주문 2단계 (확인 후 확정)
에이전트가 사용자 확인 없이 주문하지 않도록 **서버가 두 단계로 나눕니다.** LLM 이 같은 요청을 다시 보내도 주문은 한 번만 생깁니다.

1. `POST /orders/prepare` — 상품·옵션·재고를 검사하고 **요약과 `confirmToken`** 을 돌려줍니다. 아직 주문되지 않습니다.
2. 앱이 요약을 읽어 주고 사용자가 "네" 라고 답하면
3. `POST /orders/confirm` `{"confirmToken": ...}` — 주문을 만들고 재고를 줄입니다.
   같은 토큰을 다시 보내면 **새 주문 없이** 그때 만든 주문을 `alreadyConfirmed: true` 로 돌려줍니다.

토큰은 10분 뒤 만료되고, 같은 사용자가 prepare 를 다시 하면 앞의 토큰은 무효가 됩니다.
오류 본문의 `detail.code` 로 앱이 갈래를 나눕니다: `MISSING_OPTION` 되묻기 · `OUT_OF_STOCK` 다른 상품 권하기 · `TOKEN_*` 확인부터 다시.
"""

PREPARE = {
    "summary": "주문 확인 (1단계)",
    "description": (
        "주문할 줄(1~20)을 검사하고 요약과 1회용 `confirmToken` 을 돌려줍니다. **주문도 재고 차감도 하지 않습니다.**\n\n"
        "- 검사는 주문과 같습니다: 없는 상품 404, 옵션 누락·잘못된 값 422 + `choices`, 품절·재고 부족 409\n"
        "- 실패하면 문제가 된 줄의 `index`·`productId` 가 함께 옵니다\n"
        "- 같은 사용자의 이전 토큰은 무효가 됩니다"
    ),
    "responses": {
        **_error(404, "없는 상품", {"code": "PRODUCT_NOT_FOUND", "message": "없는 상품: p99999", "index": 0, "productId": "p99999"}),
        **_error(409, "품절이거나 재고가 모자람", {
            "code": "OUT_OF_STOCK", "message": "재고가 모자란다", "available": 8, "index": 0, "productId": "p03007"}),
        **_error(422, "옵션을 고르지 않았거나 없는 값. **앱은 `choices` 로 되묻습니다.**", {
            "code": "MISSING_OPTION", "message": "옵션을 골라야 한다", "missing": ["사이즈"],
            "choices": {"사이즈": ["M", "L", "XL"]}, "index": 0, "productId": "p07002"}),
    },
}

CONFIRM = {
    "summary": "주문 확정 (2단계)",
    "description": (
        "사용자가 확인에 동의했을 때만 부릅니다. prepare 때 금액으로 줄마다 주문을 만들고 재고를 줄입니다.\n\n"
        "- **같은 토큰을 다시 보내면** 새 주문 없이 그때 만든 주문을 `alreadyConfirmed: true` 로 돌려줍니다\n"
        "- prepare 뒤에 품절되거나 재고가 모자라면 409 이고 아무것도 주문하지 않습니다"
    ),
    "responses": {
        **_error(404, "없는 토큰·다른 사용자의 토큰", {
            "code": "TOKEN_NOT_FOUND", "message": "주문 확인 토큰이 없다. 주문 확인부터 다시 한다"}),
        **_error(409, "더 새로운 prepare 로 무효가 된 토큰, 또는 그사이 품절", {
            "code": "TOKEN_REVOKED", "message": "더 새로운 주문 확인이 있어 이 토큰은 무효다"}),
        **_error(410, "10분이 지나 만료된 토큰", {
            "code": "TOKEN_EXPIRED", "message": "주문 확인 시간(10분)이 지났다. 주문 확인부터 다시 한다"}),
    },
}
