package com.agentpjt.shop.agent

import com.agentpjt.shop.TestData
import com.agentpjt.shop.shop.CartLine
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.ShownList
import com.agentpjt.shop.shop.toSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {

    @Test
    fun home_isScreenAndUtterance() {
        assertEquals("지금 화면: 처음 화면\n어르신 말: \"안녕\"", turnMessage(ShopState(), "안녕"))
    }

    @Test
    fun results_numbersShownProducts_andListsOtherKnownOnes() {
        val shown = listOf(TestData.compact("p01", "상품 하나", 1000).toSummary())
        val s = ShopState(screen = Screen.Listening, returnTo = Screen.Results, shown = shown,
            known = listOf(KnownProduct("p01", "상품 하나"), KnownProduct("p09", "예전 상품")))
        val m = turnMessage(s, "첫 번째")
        assertTrue(m, m.contains("화면의 상품: 1번 p01 상품 하나 · 천 원"))
        assertTrue(m, m.contains("이번에 본 다른 상품: p09 예전 상품"))
        assertFalse(m, m.contains("다른 상품: p01"))
    }

    @Test
    fun confirm_listsCartAndTotal() {
        val cart = listOf(
            CartLine("c1", "p07002", "무릎 보호대 2개입", 16900, 2000, mapOf("사이즈" to "XL"), 1, 0),
            CartLine("c2", "p07001", "접이식 지팡이", 19900, 0, emptyMap(), 2, 3000),
        )
        val m = turnMessage(ShopState(screen = Screen.Confirm, cart = cart), "응")
        assertTrue(m, m.contains("장바구니 2가지: c1 무릎 보호대 2개입 (XL) 1개 · 만 팔천구백 원 / c2 접이식 지팡이 2개 · 사만 이천팔백 원"))
        assertTrue(m, m.contains("주문 확인 중: 배송비 포함 육만 천칠백 원"))
    }

    @Test
    fun earlierLists_keepTheirNumbers() {
        val s = ShopState(
            screen = Screen.Results,
            shown = listOf(TestData.compact("p02", "파스", 1000).toSummary()),
            lists = listOf(
                ShownList("파스", listOf(KnownProduct("p02", "파스"))),
                ShownList("보호대", listOf(KnownProduct("p11", "보호대 하나"), KnownProduct("p12", "보호대 둘"))),
            ),
        )
        val m = turnMessage(s, "아까 처음 거")
        assertTrue(m, m.contains("앞서 띄운 목록 '보호대': 1번 p11 보호대 하나 / 2번 p12 보호대 둘"))
        assertFalse(m, m.contains("앞서 띄운 목록 '파스'")) // 지금 화면의 목록은 위에 있다
    }

    @Test
    fun systemPrompt_namesEveryAction() {
        val p = systemPrompt()
        Action.Kind.entries.forEach { assertTrue(it.wire, p.contains("- ${it.wire}:")) }
    }
}
