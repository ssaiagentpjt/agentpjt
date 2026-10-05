package com.agentpjt.shop.shop

import com.agentpjt.shop.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptsTest {

    private val knee = TestData.knee.toDetail() // 16,900원, 사이즈 옵션(XL +2,000)
    private val cane = TestData.cane.toDetail() // 19,900원, 배송비 3,000원

    @Test
    fun results_tagsEachProductForHighlight() {
        val products = listOf(
            TestData.compact("p01", "쿨 파스 20매", 9900).toSummary(),
            TestData.compact("p02", "관절 플라스타 34매", 18500).toSummary(),
            TestData.compact("p03", "온열 찜질 패치 10매", 12000).toSummary(),
        )
        val lines = Scripts.results("무릎 파스", products)
        assertEquals(listOf("results-intro", "item-0", "item-1", "item-2", "results-ask"), lines.map { it.id })
        assertEquals("무릎 파스를 세 개 골랐어요.", lines[0].text)
        assertTrue(lines[1].text.contains("구천구백 원"))
    }

    @Test
    fun detail_mentionsShippingAndNextOptionToChoose() {
        assertTrue(Scripts.detail(cane).single().text.contains("배송비 삼천 원이 붙어요"))
        val text = Scripts.detail(knee).single().text
        assertTrue(text, text.contains("사이즈는 M, L, XL 중에 고르실 수 있어요"))
        assertTrue(Scripts.detail(knee, mapOf("사이즈" to "L")).single().text.contains("장바구니에 담을까요"))
    }

    @Test
    fun confirm_readsEveryLineAndTotalIncludingShippingAndOptionPrice() {
        val lines = listOf(
            CartLine("c1", "p07001", "접이식 알루미늄 지팡이", 19900, 0, emptyMap(), 2, 3000),
            CartLine("c2", "p07002", "무릎 보호대 2개입", 16900, 2000, mapOf("사이즈" to "XL"), 1, 0),
        )
        assertEquals(19900 * 2 + 3000 + 16900 + 2000, lines.total())
        val text = Scripts.confirm(lines).single().text
        assertTrue(text, text.startsWith("접이식 알루미늄 지팡이 두 개, 무릎 보호대 2개입 XL 한 개, 배송비 포함 육만 천칠백 원입니다."))
    }

    @Test
    fun say_readsCleanedShortText() {
        assertEquals("두 개 찾았어요. 어떤 게 좋으세요?", Scripts.say("[화면] 처음 화면\n**두 개** 찾았어요. 어떤 게 좋으세요? 1. p01001 햅쌀").single().text)
    }

    @Test
    fun askOption_readsChoices() {
        assertEquals("사이즈를 먼저 골라 주세요. M, L 중에 고르실 수 있어요.", Scripts.askOption(mapOf("사이즈" to listOf("M", "L"))).single().text)
    }
}
