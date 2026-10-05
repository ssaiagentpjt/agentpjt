package com.agentpjt.shop.shop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptsTest {

    private val catalog = MockCatalog()
    private val coolPatch = catalog.byId("p01")!! // 9,900원, 로켓
    private val plaster = catalog.byId("p02")!! // 18,500원, 배송비 3,000원

    @Test
    fun results_tagsEachProductForHighlight() {
        val lines = Scripts.results("무릎 파스", listOf(coolPatch, plaster, catalog.byId("p03")!!))
        assertEquals(listOf("results-intro", "item-0", "item-1", "item-2", "results-ask"), lines.map { it.id })
        assertEquals("무릎 파스를 세 개 골랐어요.", lines[0].text)
        assertTrue(lines[1].text.contains("구천구백 원"))
    }

    @Test
    fun confirm_readsTotalIncludingShipping() {
        assertEquals(40_000, Scripts.total(plaster, 2))
        val text = Scripts.confirm(plaster, 2).single().text
        assertTrue(text, text.startsWith("관절 플라스타 34매, 두 개, 배송비 포함 사만 원입니다."))
    }

    @Test
    fun detail_mentionsShippingOnlyWhenCharged() {
        assertTrue(Scripts.detail(plaster).single().text.contains("배송비 삼천 원이 붙어요"))
        assertTrue(Scripts.detail(coolPatch).single().text.contains("배송비는 없어요"))
    }
}
