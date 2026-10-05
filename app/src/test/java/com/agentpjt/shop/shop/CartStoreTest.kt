package com.agentpjt.shop.shop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CartStoreTest {

    @get:Rule val dir = TemporaryFolder()

    private val lines = listOf(
        CartLine("c1", "p01002", "무가당 두유 24팩", 15800, 0, emptyMap(), 2, 0),
        CartLine("c3", "p07002", "무릎 보호대 2개입", 16900, 2000, mapOf("사이즈" to "XL"), 1, 0, "https://x/i.svg"),
    )

    @Test
    fun saveThenLoad_keepsLines() {
        val file = dir.root.resolve("cart.json")
        CartStore(file).save(lines)
        assertEquals(lines, CartStore(file).load())
        CartStore(file).save(lines.take(1)) // 덮어쓰기
        assertEquals(lines.take(1), CartStore(file).load())
    }

    @Test
    fun missingOrBrokenFile_isEmptyCart() {
        val file = dir.root.resolve("cart.json")
        assertTrue(CartStore(file).load().isEmpty())
        file.writeText("{깨진")
        assertTrue(CartStore(file).load().isEmpty())
    }

    @Test
    fun nextLineId_neverReusesNumbers() {
        assertEquals("c4", lines.nextLineId())
        assertEquals("c1", emptyList<CartLine>().nextLineId())
    }
}
