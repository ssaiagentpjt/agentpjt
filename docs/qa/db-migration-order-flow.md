## 🗄️ 서버 DB 마이그레이션 안내 — feat/order-flow (v0.6.0)
아직 **로컬 브랜치**이고 공개 서버는 v0.5.0 그대로입니다. 머지 전에 공유합니다.
머지하면 watchtower 가 새 이미지로 재시작하면서 `/srv/var/shop.db` 가 **자동으로** 마이그레이션됩니다(수동 작업 없음).

**1. orders 테이블 — 열 5개 추가 (기존 열·데이터는 그대로)**
```
status       기본 CONFIRMED   CONFIRMED · CANCELLED · DELIVERED
token        빈 값 허용        어느 주문 확인 토큰으로 만든 주문인지
unitPrice    빈 값 허용        주문 때 단가(가격 + 옵션 추가 금액)
shippingFee  빈 값 허용        주문 때 배송비
cancelledAt  빈 값 허용        취소 시각
```
인덱스 `orders_token` 추가

**2. 새 테이블 2개**
• `order_tokens`: 주문 확인 토큰(prepare → confirm). PENDING · USED · REVOKED, 10분 만료
• `stock_ledger`: 재고 변동 기록(주문 −, 취소 +). 서버 시작 때 시드 재고에 합계를 다시 반영 → 재시작해도 재고 유지

**3. 기존 데이터에 생기는 일**
• 지금까지 쌓인 시연 주문 → `CONFIRMED`(취소 가능). token · unitPrice 는 빈 값
• 시드의 지난 주문 5건(u001) → `DELIVERED`(취소 불가)
• 기존 주문은 재고 기록이 없어서 취소해도 재고를 늘리지 않음(줄인 적이 없으니까)
• 상품 테이블은 지금처럼 매번 시드로 다시 만듦. 달라지는 건 재고 수량뿐

**4. API(JSON)는 추가만 있음**
• 기존 응답: 주문에 `status`, 주문 오류 detail 에 `code` · `available` 추가. 빠지거나 이름이 바뀐 필드 없음
• 새 경로: `POST /orders/prepare` · `/orders/confirm` · `/orders/{id}/cancel`
⚠️ 앱 JSON 파서가 모르는 필드에서 실패하는 설정이면(ignoreUnknownKeys=false) 확인 필요
⚠️ 이제 주문하면 재고가 실제로 줄어듦 → 테스트 주문을 반복하면 재고 적은 상품은 409 가 날 수 있음

**5. 검증**
• 옛 형식 DB를 새 코드로 열기 → 기존 주문 그대로, 상태 정상
• 마이그레이션된 DB를 옛 코드(v0.5.0)로 열기 → 정상 동작, **롤백 가능** (단, 롤백하면 재고는 시드 값으로 돌아감)
• pytest 105개 통과

**머지 전 권장**: shop.db 백업 한 번
`docker compose cp shop-api:/srv/var/shop.db ./shop.db.bak`
