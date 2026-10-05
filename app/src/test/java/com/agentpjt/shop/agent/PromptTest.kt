package com.agentpjt.shop.agent

import com.agentpjt.shop.TestData
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.toDetail
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
    fun confirm_hasProductFactsAndTotal() {
        val s = ShopState(screen = Screen.Confirm, current = TestData.knee.toDetail(), selected = mapOf("사이즈" to "XL"), qty = 1)
        val m = turnMessage(s, "응")
        assertTrue(m, m.contains("옵션 사이즈(M/L/XL +이천 원/3XL 품절)"))
        assertTrue(m, m.contains("주문 확인 중: 배송비 포함 만 팔천구백 원")) // 16,900 + XL 2,000
    }

    @Test
    fun systemPrompt_namesEveryAction() {
        val p = systemPrompt()
        Action.Kind.entries.forEach { assertTrue(it.wire, p.contains("- ${it.wire}:")) }
    }
}
