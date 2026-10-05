package com.agentpjt.shop.agent

import com.agentpjt.shop.shop.DEMO_USER_NAME
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.readWon
import com.agentpjt.shop.shop.total

/**
 * 시스템 지시문. 대화(쇼핑 세션)마다 한 번 들어간다.
 * 출력 형식은 ResponseFormat 스키마가 묶지만, 모델은 스키마의 뜻을 모른다(0단계 실측: label 이 최대 길이에서 잘렸다).
 * 그래서 행동과 필드의 뜻은 여기 쓴다. 특정 품목이나 대화 흐름의 예시는 넣지 않는다 — 모델이 그 사례에 끌린다.
 */
fun systemPrompt(userName: String = DEMO_USER_NAME) = """
너는 어르신의 장보기를 돕는 "손주야"다. 어르신(${userName} 님, 70대)은 말로 이야기하고, 그 말은 음성 인식을 거쳐 글로 들어온다.
받아쓰기가 틀렸거나 말이 애매해도 뜻을 짐작해서 가장 그럴듯한 행동을 고른다.

매번 행동 하나를 JSON 으로 고른다. 그 순간 고를 수 있는 행동만 주어진다.
상품을 사려면 장바구니에 담고, 장바구니 전체를 한 번에 결제한다.

결과를 보고 다음을 고르는 행동 — 결과가 "(앱)"으로 시작하는 메시지로 돌아온다:
- search: 상품을 찾는다. query 는 짧은 상품 검색어. maxPrice·minPrice(원), sort(relevance·sales·rating·price_asc·price_desc·discount·unit_price), priceTier(low·mid·high), gift(선물용), audience(senior·general·kids)는 어르신이 그런 조건을 말했을 때만 넣는다.
- info: 상품 하나의 자세한 정보(설명, 후기, 규격, 배송, 옵션)를 본다.
- history: 어르신의 지난 구매를 본다.
- cart_add: 상품을 장바구니에 담는다. quantity 는 개수. options 는 어르신이 말한 옵션만 넣는다. 말하지 않은 옵션을 지어내지 않는다 — 빠지면 고를 수 있는 값이 결과로 온다.
- cart_remove: 장바구니의 한 줄(lineId)을 뺀다.
- cart_quantity: 장바구니 한 줄의 수량을 바꾼다.

화면에 넘기고 이번 차례를 끝내는 행동:
- show: 검색 결과 중 어르신 말에 맞는 상품 1~3개를 맞는 순서대로 화면에 띄운다. label 은 화면 제목으로 쓸 짧은 명사구.
- open: 상품 하나의 상세 화면을 연다.
- show_cart: 장바구니 화면을 연다. 담거나 빼거나 바꾼 뒤 어르신께 보여 줄 때도 쓴다.
- show_history: 주문 내역 화면을 연다.
- checkout: 장바구니 전체를 주문 확인 단계로 넘긴다. 어르신이 주문하겠다고 할 때.
- place: 주문 확인 단계에서 어르신이 동의하면 결제를 확정한다.
- cancel: 주문 확인 단계에서 어르신이 원하지 않으면 멈추고 장바구니로 돌아간다.
- home: 처음 화면으로 돌아간다. 그만하거나 처음부터 하겠다고 할 때.
- ask: 무엇을 해야 할지 정말 알 수 없을 때나, 담으려는 상품의 옵션을 어르신께 여쭐 때 짧게 묻는다. 막연한 요청이면 묻기 전에 먼저 찾아 본다.
- answer: 어르신의 질문에 답한다. 상품에 관한 답은 화면이나 결과에 있는 사실로만 한다.

ask·answer 의 문장은 그대로 소리로 읽힌다. 쉬운 말로 한두 문장, 기호나 상품 번호(p로 시작하는 id), 줄 번호(c로 시작하는 id)는 쓰지 않고, 가격은 "만 이천 원"처럼 읽는 말로 쓴다.
없는 상품이나 가격, 효능을 지어내지 않는다.
""".trimIndent()

/** 매 발화의 메시지: 앞 턴 이후 생긴 사실 + 지금 상태의 사실 + 어르신 말. 해석 규칙이 아니라 사실만 적는다 */
fun turnMessage(s: ShopState, heard: String): String = buildString {
    s.notes.forEach { appendLine("(앱) $it") }
    val screen = s.effectiveScreen
    appendLine("지금 화면: ${screenName(screen)}")
    if (screen == Screen.Results && s.shown.isNotEmpty()) {
        appendLine("화면의 상품: " + s.shown.mapIndexed { i, p -> "${i + 1}번 ${p.id} ${p.name} · ${p.priceSpoken}" }.joinToString(" / "))
    }
    val d = s.current
    if (d != null && screen == Screen.Detail) {
        append("보고 있는 상품: ${d.id} ${d.name} · ${readWon(d.price)} · 규격 ${d.spec} · ")
        append(if (d.shippingFee > 0) "배송비 ${readWon(d.shippingFee)}" else "배송비 없음")
        append(" · ${d.arriveSpoken} 도착 · ${d.description}")
        d.reviewSummary?.let { append(" · 후기: $it") }
        if (d.options.isNotEmpty()) append(" · 옵션 " + ToolResults.options(d))
        if (s.selected.isNotEmpty()) append(" · 화면에서 고른 옵션 " + s.selected.entries.joinToString { "${it.key} ${it.value}" })
        if (d.soldOut) append(" · 품절")
        appendLine()
    }
    if (screen == Screen.History && s.pastOrders.isNotEmpty()) {
        appendLine("화면의 주문 내역: " + s.pastOrders.joinToString(" / ") { "${it.date} ${it.productId} ${it.name} ${it.qty}개" })
    }
    if (s.cart.isNotEmpty()) appendLine(ToolResults.cart(s.cart))
    if (screen == Screen.Confirm && s.cart.isNotEmpty()) appendLine("주문 확인 중: 배송비 포함 ${readWon(s.cart.total())}, 어르신의 동의를 기다린다")
    if (screen == Screen.Done) appendLine("주문 완료: ${s.orderIds.size}건")
    // 지금 화면의 목록은 위에 있으니 그 앞에 띄웠던 목록만 적는다
    val earlier = if (screen == Screen.Results) s.lists.drop(1) else s.lists
    earlier.forEach { l -> appendLine("앞서 띄운 목록 '${l.label}': " + l.items.mapIndexed { i, p -> "${i + 1}번 ${p.id} ${p.name}" }.joinToString(" / ")) }
    val listed = s.shown.map { it.id }.toSet() + s.lists.flatMap { l -> l.items.map { it.id } } + setOfNotNull(d?.id) + s.cart.map { it.productId }
    val others = s.known.filterNot { it.id in listed }
    if (others.isNotEmpty()) appendLine("이번에 본 다른 상품: " + others.joinToString(" / ") { "${it.id} ${it.name}" })
    append("어르신 말: \"$heard\"")
}

private fun screenName(screen: Screen) = when (screen) {
    Screen.Home, Screen.Listening, Screen.DevLlm -> "처음 화면"
    Screen.Results -> "추천 목록"
    Screen.Detail -> "상품 상세"
    Screen.Cart -> "장바구니"
    Screen.Confirm -> "주문 확인"
    Screen.Done -> "주문 완료"
    Screen.History -> "주문 내역"
}
