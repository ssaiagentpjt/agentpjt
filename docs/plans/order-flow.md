# 주문 2단계 · 중복 방지 · 재고 · 취소 · 완료 요약 구현 계획

기준: main `6e2fbc0` (서버 v0.5.0, pytest 69개), 2026-10-05
대상: 백엔드 테스트(`docs/qa/server-test-2026-10-05.md`)에서 실패한 G-01~G-05. 개인 설정(G-06)은 뺀다.
범위: `server/` 만 바꾼다. 앱 연결은 앱 담당(bo-mun)과 맞춘 뒤 따로 한다(7절).

| TC | 실패 내용 | 이번에 하는 것 |
|---|---|---|
| G-01 | 같은 주문을 다시 보내면 2건 생김 | 1회용 토큰으로 확정, 같은 토큰은 같은 주문을 돌려줌 |
| G-02 | 주문해도 재고 그대로 | 확정 때 재고를 다시 검사하고 차감 |
| G-03 | 취소 API 없음 | 줄 단위 취소 + 재고 복원 |
| G-04 | 2단계 주문 없음 | `prepare` → `confirm` |
| G-05 | 완료 요약 없음 | 확정·취소 응답에 `summaryText` |

## 1. 바뀐 상황 (이전 계획 대비)

- `POST /orders/batch` 가 생겼고, 앱에 장바구니가 들어오는 중이다(커밋 6e2fbc0 본문 "8단계").
  그래서 2단계 주문도 **여러 줄(장바구니)** 을 받는다. 단건 주문은 줄이 하나인 경우로 본다.
- 검사(`_check_line`)와 기록(`_insert_order`)이 이미 나뉘어 있다. prepare 는 `_check_line` 을, confirm 은 `_insert_order` 를 그대로 쓴다.

## 2. API

모든 주문 오류는 지금처럼 `{"detail": {...}}` 로 오고, `detail` 에 `code` 만 더한다. 기존 필드(`choices`, `index` 등)는 그대로라 앱이 깨지지 않는다.

### 2.1 `POST /orders/prepare` — 주문 요약 + 확인 토큰

요청: `/orders/batch` 와 같은 `{userId, items: [{productId, quantity, options}]}` (1~20줄)

```json
200
{
  "confirmToken": "q3V0...",                  // secrets.token_urlsafe(16)
  "expiresAt": "2026-10-05T18:10:00+09:00",   // 10분
  "items": [{"productId": "p11037", "productName": "새치 염색약 1회분", "options": {"색상": "흑갈색"},
             "quantity": 2, "unitPrice": 8900, "shippingFee": 0, "totalPrice": 17800}],
  "totalPrice": 17800, "totalSpoken": "만 칠천팔백 원",
  "summaryText": "새치 염색약 1회분 흑갈색 2개, 모두 만 칠천팔백 원이에요. 주문할까요?"
}
```

- 검사는 `_check_line` 그대로(404 · 422+`choices` · 409, 실패 줄은 `index`·`productId`)이고, **재고 수량 검사**를 더한다.
  같은 상품이 여러 줄에 있으면 수량을 합쳐서 본다. 모자라면 409 `OUT_OF_STOCK` + 남은 수량.
- 같은 사용자의 이전 PENDING 토큰은 REVOKED 로 바꾼다(확인 화면을 다시 연 경우).
- 주문도, 재고 차감도 일어나지 않는다.

### 2.2 `POST /orders/confirm` — 확정 (멱등)

요청: `{userId, confirmToken}`

| 경우 | 응답 |
|---|---|
| 처음 confirm | 200, 줄마다 주문 생성 + 재고 차감, `alreadyConfirmed: false` |
| 같은 토큰으로 다시 | 200, **같은 주문들**, `alreadyConfirmed: true`, 재고 그대로 |
| 없는 토큰 · 빈 토큰 · 다른 사용자의 토큰 | 404 `TOKEN_NOT_FOUND` |
| 10분 지남 | 410 `TOKEN_EXPIRED` |
| 다시 prepare 해서 무효 | 409 `TOKEN_REVOKED` |
| 그사이 품절 · 재고 부족 | 409 `OUT_OF_STOCK` (+ `index`), 아무것도 주문하지 않음 |

응답: `/orders/batch` 응답(`orders`, `totalPrice`)에 `alreadyConfirmed`, `arriveSpoken`, `summaryText` 를 더한다.
금액은 **prepare 때 금액**으로 확정한다(사용자가 듣고 동의한 금액).

`summaryText` 예: `"주문했어요. 새치 염색약 1회분 흑갈색 2개, 모두 만 칠천팔백 원이에요. 내일 도착해요."`
여러 줄이면 `"주문했어요. 새치 염색약 외 1개, 모두 … 원이에요. 늦어도 10월 8일에 도착해요."` (가장 늦은 도착일)

### 2.3 `POST /orders/{orderId}/cancel` — 취소 (줄 단위)

요청: `{userId}`

| 경우 | 응답 |
|---|---|
| CONFIRMED 주문 | 200, CANCELLED, 재고 복원 |
| 두 번째 취소 | 409 `ALREADY_CANCELLED` |
| 시드의 지난 주문(배송 완료) | 409 `NOT_CANCELLABLE` |
| 없는 주문 · 다른 사용자의 주문 | 404 `ORDER_NOT_FOUND` |

응답: `{orderId, status, cancelledAt, refundPrice, summaryText}`
`summaryText` 예: `"새치 염색약 1회분 주문을 취소했어요. 만 칠천팔백 원은 돌려 드려요."`

구매 이력은 지금도 줄 단위라 취소도 줄 단위로 한다. "방금 주문한 거 다 취소" 는 앱이 confirm 응답의 주문번호들로 부른다.

### 2.4 기존 엔드포인트

- `POST /orders`, `POST /orders/batch`: 앱이 쓰고 있으므로 **남기되**, 같은 잠금·재고 차감 경로를 타게 한다(G-02 는 여기서도 고쳐진다).
  Swagger 에 "prepare/confirm 으로 옮길 예정" 이라고 적는다. 앱이 옮겨 간 뒤 지워야 "서버가 확인 없는 주문을 막는다" 가 완성된다.
- `GET /users/{id}/orders`: 각 주문에 `status` (CONFIRMED · CANCELLED · DELIVERED) 를 더한다.

### 2.5 오류 code

| code | 상태 | 쓰는 곳 |
|---|---|---|
| `PRODUCT_NOT_FOUND` | 404 | 모든 주문 |
| `MISSING_OPTION` | 422 | 모든 주문 (옵션 누락 · 없는 값) |
| `OUT_OF_STOCK` | 409 | 모든 주문 (상품 품절 · 옵션 품절 · 수량 부족) |
| `TOKEN_NOT_FOUND` · `TOKEN_EXPIRED` · `TOKEN_REVOKED` | 404 · 410 · 409 | confirm |
| `ORDER_NOT_FOUND` · `ALREADY_CANCELLED` · `NOT_CANCELLABLE` | 404 · 409 · 409 | cancel |

## 3. 저장 구조

주문 쪽 테이블은 재시작해도 남는다. 옛 DB 는 지금의 `migrate_orders` 방식(`alter table … add column`)으로 고친다.

```sql
create table if not exists order_tokens (
  token text primary key, userId text not null,
  items text not null,                 -- JSON: 줄마다 productId·quantity·options·unitPrice·shippingFee·totalPrice
  totalPrice integer not null,
  status text not null,                -- PENDING · USED · REVOKED
  createdAt text not null, expiresAt text not null);

-- orders 에 덧붙이는 열
status text not null default 'CONFIRMED',   -- CONFIRMED · CANCELLED · DELIVERED
token text,                                  -- 어느 확정으로 만든 주문인지. 같은 토큰 재확정 때 이걸로 찾는다
unitPrice integer, shippingFee integer, cancelledAt text

-- 재고 변동 기록. 상품 테이블은 시작할 때마다 시드로 다시 만들어지므로,
-- 다시 만든 뒤 이 합계를 반영해야 재시작해도 재고가 유지된다
create table if not exists stock_ledger (
  id integer primary key, productId text not null, delta integer not null,
  orderId text not null, reason text not null, at text not null);   -- ORDER · CANCEL
```

- 재고 상태는 수량에서 다시 정한다: 0 → `sold_out`, 1~20 → `low`, 21 이상 → `in_stock` (`validate.py` 규칙과 같다).
- 옵션 재고는 수량이 아니라 상태값이라 건드리지 않는다.
- 시드의 지난 주문은 시작할 때 `DELIVERED` 로 둔다.
- 재고 기록이 없는 주문(이 기능 전에 공개 서버에 쌓인 주문)은 취소해도 재고를 늘리지 않는다.
- `unitPrice` 를 지금부터 저장해 두면 다음 작업인 재주문의 가격 변동(priceChanged)에 쓸 수 있다.

## 4. 동시성과 시간

- **잠금**: `Store` 에 `threading.Lock` 을 두고 주문 쓰기(prepare · confirm · cancel · 기존 orders · batch)를 한 번에 하나씩 처리한다.
  SQLite 연결 하나를 여러 스레드가 나눠 쓰므로(`check_same_thread=False`) 잠금이 없으면 트랜잭션이 섞인다.
- **상태 전이**: confirm 은 `update order_tokens set status='USED' where token=? and status='PENDING'` 의 바뀐 행 수가 1일 때만 주문을 만든다.
  주문 생성 · 재고 차감 · 기록 · 토큰 상태를 한 트랜잭션에서 하고, 실패하면 rollback 한다(batch 와 같은 방식).
- **시계 주입**: `Store(..., clock=...)`, `create_app(..., clock=None)`. 기본은 `datetime.now(KST)`. 테스트는 바꿀 수 있는 시계로 만료를 검사한다.

## 5. 테스트 (`tests/test_order_flow.py`, 픽스처 상품 28개)

**G-04 2단계 / G-01 중복 방지**
- prepare 만 하면 주문 수가 그대로다.
- 지어낸 토큰 · 빈 토큰으로 confirm 하면 404 이고 주문 수가 그대로다.
- 같은 토큰으로 두 번 confirm 하면 같은 주문번호들, 두 번째는 `alreadyConfirmed: true`, 재고는 한 번만 준다.
- 같은 토큰으로 동시에 5번(스레드 5개) confirm 하면 주문 1번, 재고 기록 1번이다.
- 시계를 10분 넘기면 410, prepare 를 다시 하면 이전 토큰 409, 다른 사용자가 쓰면 404.
- 여러 줄 prepare 에서 한 줄이 틀리면 `index` 와 함께 422/409.

**G-02 재고**
- confirm 하면 재고가 수량만큼 준다. 21 아래로 내려가면 `low`, 0 이면 `sold_out` 이고 검색에서 빠진다.
- prepare 뒤 다른 주문이 재고를 가져가면 confirm 이 409 (픽스처 `p03007`, 재고 8개).
- 같은 상품 두 줄의 수량 합이 재고보다 많으면 409.
- 기존 `POST /orders` · `/orders/batch` 도 재고를 줄인다.
- 같은 DB 로 앱을 다시 만들어도(재시작) 줄어든 재고가 유지된다.

**G-03 취소**
- 취소하면 CANCELLED, 재고가 돌아온다. 두 번째 취소 409.
- 시드 주문 409, 다른 사용자의 주문 404.
- 옛 DB(새 열이 없는 orders)로 시작해도 열리고, 기존 주문은 CONFIRMED.

**G-05 요약**
- 완료 · 취소 요약에 상품명, 옵션 값, 수량, 금액(읽는 말), 도착일이 들어간다. 여러 줄은 "외 N개".
- confirm 응답 크기 1KB 이하(온디바이스 모델 입력).

**기존 동작**
- 지금 테스트 69개가 그대로 통과한다.
- 백엔드 시나리오 스크립트를 다시 돌려 G-01~G-05 가 통과로 바뀌는지 확인한다.

매 커밋마다 `ruff check .`, `pytest -q`, `python -m app.validate --strict` 를 통과시킨다.

## 6. 커밋 순서 (브랜치 `feat/order-flow`, 로컬 커밋만)

1. `refactor(server)`: Store 에 시계 주입 · 주문 쓰기 잠금을 넣고 주문 오류에 `code` 를 더한다 — 동작 변화 없음
2. `feat(server)`: 주문하면 재고를 줄이고 재시작해도 유지한다 (G-02) — 재고 기록, 수량 검사, 기존 두 엔드포인트에도 적용
3. `feat(server)`: 주문을 prepare → confirm 2단계로 나누고 같은 토큰의 중복 확정을 막는다 (G-04, G-01)
4. `feat(server)`: 주문 취소와 재고 복원을 추가한다 (G-03) — 주문 상태, 시드 주문 DELIVERED
5. `feat(server)`: 주문 완료 · 취소 요약 summaryText 를 더한다 (G-05) — Swagger 주문 흐름 설명, 버전 0.6.0

재고(2)를 2단계(3)보다 먼저 하는 이유: 기존 엔드포인트만으로도 G-02 가 고쳐지고, 3 이 그 경로를 그대로 쓰기 때문이다.

## 7. 앱 연결 (앱 담당과 상의, 이번 작업에서는 하지 않는다)

| 앱 | 서버 |
|---|---|
| 확인 화면 열기 (`requestOrder`, 장바구니 결제 확인) | `POST /orders/prepare`. 토큰을 `ShopState` 에 둔다 |
| 주문 확정 (`placeOrder`) | `POST /orders/confirm`. 화면 상태 검사는 그대로 둔다(이중 안전) |
| (새) 주문 취소 | `POST /orders/{id}/cancel` |
| 오류 처리 | `code` 로 나눈다. `MISSING_OPTION` → `choices` 로 되묻기, `OUT_OF_STOCK` → 다른 상품 권하기, `TOKEN_*` → 확인 화면 다시 열기 |

## 8. 주의할 점

- **main 에 머지되면 곧 공개 서버(`shop-api.bomun.dev`)에 반영되고 운영 DB 가 마이그레이션된다.** 이번에는 로컬 커밋까지만 하고, 푸시 · PR 은 서버 담당(bo-mun) 리뷰를 거친다.
- bo-mun 이 같은 파일(`store.py`, `main.py`, `models.py`, `docs.py`)을 계속 고치고 있으므로, 시작 전에 pull 하고 커밋을 작게 나눠 충돌을 줄인다.
- 잠금 때문에 주문 요청은 한 번에 하나씩 처리된다. 시연 규모에서는 문제가 없다.
