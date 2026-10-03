package com.agentpjt.shop.shop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptsTest {

    @Test
    fun results_tagsEachProductForHighlight() {
        val lines = Scripts.results(MOCK_KEYWORD, MOCK_PRODUCTS)
        assertEquals(listOf("results-intro", "item-0", "item-1", "item-2", "results-ask"), lines.map { it.id })
        assertEquals("무릎 파스를 세 개 찾았어요.", lines[0].text)
        assertTrue(lines[1].text.contains("구천구백 원"))
    }

    @Test
    fun confirm_readsTotalIncludingShipping() {
        val ketotop = MOCK_PRODUCTS[1] // 18,500원, 배송비 3,000원
        assertEquals(40_000, Scripts.total(ketotop, 2))
        val text = Scripts.confirm(ketotop, 2).single().text
        assertTrue(text, text.startsWith("케토톱 플라스타 두 개, 배송비 포함 사만 원입니다."))
    }

    @Test
    fun detail_mentionsShippingOnlyWhenCharged() {
        assertTrue(Scripts.detail(MOCK_PRODUCTS[1]).single().text.contains("배송비 삼천 원이 붙어요"))
        assertTrue(Scripts.detail(MOCK_PRODUCTS[0]).single().text.contains("배송비는 없어요"))
    }
}
