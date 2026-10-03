package com.agentpjt.shop.agent

import com.agentpjt.shop.shop.MockCatalog
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    private val catalog = MockCatalog()
    private val exec = ToolExecutor(catalog)
    private val home = ShopState()

    @Suppress("UNCHECKED_CAST")
    private fun products(response: Map<String, Any?>) = response["products"] as List<Map<String, Any?>>

    @Test
    fun getProducts_appliesPriceCapOnly() {
        val all = exec.execute("getProducts", mapOf("category" to "파스·찜질"), home)
        val capped = exec.execute("getProducts", mapOf("category" to "파스·찜질", "maxPrice" to 13_000.0), home)
        assertEquals(4, products(all.response).size)
        assertEquals(listOf("p01", "p03"), products(capped.response).map { it["id"] })
        assertEquals("구천구백 원", products(capped.response)[0]["priceSpoken"])
        assertFalse(all.navigated)
    }

    @Test
    fun getProducts_unknownCategory_listsValidOnes() {
        val out = exec.execute("get_products", mapOf("category" to "파스"), home)
        assertEquals(false, out.response["ok"])
        assertTrue(out.response["error"].toString().contains("파스·찜질"))
    }

    @Test
    fun showProducts_navigatesWithChosenOrder() {
        val out = exec.execute("show_products", mapOf("product_ids" to listOf("p03", "p01"), "label" to "무릎 파스"), home)
        assertTrue(out.navigated)
        assertEquals(Screen.Results, out.state.screen)
        assertEquals(listOf("p03", "p01"), out.state.shown.map { it.id })
        assertEquals("무릎 파스", out.state.label)
    }

    @Test
    fun showProducts_rejectsUnknownIds() {
        val out = exec.execute("showProducts", mapOf("productIds" to listOf("p01", "x99"), "label" to "a"), home)
        assertFalse(out.navigated)
        assertEquals(Screen.Home, out.state.screen)
    }

    @Test
    fun setQuantity_needsProductAndRange() {
        assertEquals(false, exec.execute("setQuantity", mapOf("count" to 2), home).response["ok"])
        val detail = exec.execute("openProduct", mapOf("productId" to "p02"), home).state
        assertEquals(false, exec.execute("setQuantity", mapOf("count" to 12), detail).response["ok"])
        assertEquals(3, exec.execute("setQuantity", mapOf("count" to 3L), detail).state.qty)
    }

    @Test
    fun placeOrder_onlyFromConfirmScreen() {
        val detail = exec.execute("openProduct", mapOf("productId" to "p02"), home).state
        val refused = exec.execute("placeOrder", emptyMap(), detail)
        assertEquals(false, refused.response["ok"])
        assertEquals(Screen.Detail, refused.state.screen)

        val confirm = exec.execute("requestOrder", mapOf("productId" to "p02", "quantity" to 2), detail).state
        assertEquals(Screen.Confirm, confirm.screen)
        // 확인 화면에서 "말로 하기"를 누른 상태
        val listening = confirm.copy(screen = Screen.Listening, returnTo = Screen.Confirm)
        val done = exec.execute("place_order", emptyMap(), listening)
        assertEquals(Screen.Done, done.state.screen)
        assertTrue(done.state.orderId!!.startsWith("M-20261003-"))
    }

    @Test
    fun unknownTool_isReportedNotThrown() {
        assertEquals(false, exec.execute("deleteEverything", emptyMap(), home).response["ok"])
    }

    @Test
    fun describeScreen_listsShownProductsWithNumbers() {
        val shown = exec.execute("showProducts", mapOf("productIds" to listOf("p01", "p02")), home).state
        val line = describeScreen(shown.copy(screen = Screen.Listening, returnTo = Screen.Results))
        assertTrue(line, line.startsWith("[화면 추천 목록 | 1) p01 쿨 파스 20매 9,900원 / 2) p02"))
    }
}
