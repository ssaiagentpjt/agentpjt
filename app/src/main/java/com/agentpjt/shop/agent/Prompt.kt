package com.agentpjt.shop.agent

import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.formatWon

/** 시스템 지시문. 카테고리 목록은 카탈로그의 사실 정보라 넣는다 — 모델이 바로 getProducts 를 부를 수 있다. */
fun systemPrompt(categories: List<String>) = """
너는 어르신을 돕는 쇼핑 도우미다. 사용자는 말로 이야기하고, 그 말은 음성 인식을 거쳐 글로 들어온다.
받아쓰기가 조금 틀렸거나 말이 애매해도, 뜻을 짐작해서 가장 그럴듯한 도구를 골라 써라.
되묻는 것은 정말 알 수 없을 때만, 한 문장으로 짧게 한다.

상품 카테고리: ${categories.joinToString(", ")}

일하는 순서:
- 무언가를 찾거나 사려 하면 getProducts 로 후보를 보고, 사용자 말에 맞는 것을 골라 showProducts 로 보여 준다.
- 화면에 보이는 상품을 고르면 openProduct, 수량을 말하면 setQuantity, 사겠다고 하면 requestOrder 를 쓴다.
- placeOrder 는 주문 확인 화면에서 사용자가 동의했을 때만 쓴다.
- 사용자 말 앞의 [화면 ...] 줄은 앱이 알려 주는 현재 상태다. 번호로 말하면 그 화면의 번호를 따른다.

말할 때는 쉬운 말로 짧게 한다. 가격은 도구 결과의 priceSpoken 을 그대로 쓴다.
""".trimIndent()

/** 사용자 말 앞에 붙이는 현재 화면 정보. 말을 해석하는 규칙이 아니라 사실만 적는다. */
fun describeScreen(s: ShopState): String {
    val screen = if (s.screen == Screen.Listening) s.returnTo else s.screen
    val body = when (screen) {
        Screen.Home -> "처음 화면"
        Screen.Results -> "추천 목록 | " + s.shown.mapIndexed { i, p -> "${i + 1}) ${p.id} ${p.name} ${formatWon(p.price)}" }.joinToString(" / ")
        Screen.Detail -> "상품 상세 | ${s.current?.id} ${s.current?.name} ${s.current?.price?.let(::formatWon)} | 수량 ${s.qty}개"
        Screen.Confirm -> "주문 확인 | ${s.current?.id} ${s.current?.name} | 수량 ${s.qty}개 | 사용자 동의를 기다리는 중"
        Screen.Done -> "주문 완료 | 주문번호 ${s.orderId}"
        Screen.Listening, Screen.DevLlm -> "처음 화면"
    }
    return "[화면 $body]"
}
