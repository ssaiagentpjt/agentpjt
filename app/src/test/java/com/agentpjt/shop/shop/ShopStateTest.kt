package com.agentpjt.shop.shop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopStateTest {

    private var id = 0L
    private fun ai(text: String) = ChatMsg(++id, ChatMsg.From.AI, text)
    private fun me(text: String) = ChatMsg(++id, ChatMsg.From.USER, text)
    private fun restart() = ChatMsg(++id, ChatMsg.From.SYSTEM, "새로 시작했어요")

    @Test
    fun sameAiLineInARow_isNotRepeated_butUserLinesAlwaysAre() {
        val s = ShopState().withMessage(ai("장바구니에 두 가지예요")).withMessage(ai("장바구니에 두 가지예요"))
            .withMessage(me("네")).withMessage(me("네"))
        assertEquals(listOf("장바구니에 두 가지예요", "네", "네"), s.log.map { it.text })
    }

    @Test
    fun restartDivider_notAtStartOrTwiceInARow() {
        var s = ShopState().withMessage(restart())
        assertTrue(s.log.isEmpty())
        s = s.withMessage(ai("안녕하세요")).withMessage(restart()).withMessage(restart())
        assertEquals(listOf(ChatMsg.From.AI, ChatMsg.From.SYSTEM), s.log.map { it.from })
    }

    @Test
    fun log_keepsLatest50() {
        var s = ShopState()
        repeat(60) { s = s.withMessage(me("말 $it")) }
        assertEquals(MAX_LOG, s.log.size)
        assertEquals("말 59", s.log.last().text)
    }

    @Test
    fun freshSession_keepsLogAndCart() {
        val s = ShopState(cart = listOf(CartLine("c1", "p1", "두유", 1000, 0, emptyMap(), 1, 0))).withMessage(ai("안녕하세요"))
            .copy(known = listOf(KnownProduct("p1", "두유")))
        val fresh = s.freshSession()
        assertEquals(1, fresh.log.size)
        assertEquals(1, fresh.cart.size)
        assertTrue(fresh.known.isEmpty())
    }
}
