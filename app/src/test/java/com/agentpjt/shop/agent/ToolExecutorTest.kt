package com.agentpjt.shop.agent

import com.agentpjt.shop.FakeShopApi
import com.agentpjt.shop.TestData
import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.OrderDto
import com.agentpjt.shop.api.OrderErrorDetailDto
import com.agentpjt.shop.api.SearchDto
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    private val api = FakeShopApi().apply {
        searchResult = ApiResult.Ok(SearchDto("무릎", 2, listOf(
            TestData.compact("p07002", "무릎 보호대 2개입", 16900, mapOf("사이즈" to listOf("M", "L", "XL"))),
            TestData.compact("p03005", "무릎 전용 핫팩 파스 12매", 15800),
        )))
        products["p07002"] = TestData.knee // 사이즈 M/L/XL(+2,000), 3XL 품절
        products["p07001"] = TestData.cane // 배송비 3,000
    }
    private val exec = ToolExecutor(api)
    private val home = ShopState()

    private suspend fun add(s: ShopState, id: String, qty: Int? = null, options: Map<String, String> = emptyMap()) =
        exec.execute(Action.CartAdd(id, qty, options), s)

    @Test
    fun search_remembersCandidatesWithOptionAxes() = runTest {
        val out = exec.execute(Action.Search("무릎", maxPrice = 20000, sort = "sales"), home)
        assertEquals(listOf("p07002", "p03005"), out.state.candidates.map { it.id })
        assertEquals(listOf("M", "L", "XL"), out.state.known.first().options["사이즈"])
        assertEquals(20000, api.searches.single().maxPrice)
        assertTrue(out.result, out.result.contains("p07002 무릎 보호대 2개입 · 만 육천구백 원"))
    }

    @Test
    fun show_keepsEarlierListsForLaterReference() = runTest {
        var s = exec.execute(Action.Search("무릎"), home).state
        s = exec.execute(Action.Show(listOf("p07002"), "보호대"), s).state
        s = exec.execute(Action.Show(listOf("p03005"), "파스"), s).state
        assertEquals(Screen.Results, s.screen)
        assertEquals(listOf("파스", "보호대"), s.lists.map { it.label })
        assertFalse(exec.execute(Action.Show(listOf("p99999"), "x"), s).ok)
    }

    @Test
    fun cartAdd_usesServerPriceOptionAddAndShipping() = runTest {
        var s = add(home, "p07002", 2, mapOf("사이즈" to "XL")).state
        s = add(s, "p07001").state
        assertEquals(listOf("c1", "c2"), s.cart.map { it.lineId })
        assertEquals((16900 + 2000) * 2, s.cart[0].total)
        assertEquals(19900 + 3000, s.cart[1].total)
    }

    @Test
    fun cartAdd_sameOptionsMerge_differentOptionsSplit() = runTest {
        var s = add(home, "p07002", 1, mapOf("사이즈" to "L")).state
        s = add(s, "p07002", 2, mapOf("사이즈" to "L")).state
        s = add(s, "p07002", 1, mapOf("사이즈" to "M")).state
        assertEquals(listOf(3, 1), s.cart.map { it.qty })
    }

    @Test
    fun cartAdd_missingOrSoldOutOption_isNotAdded_andReturnsChoices() = runTest {
        val missing = add(home, "p07002")
        assertFalse(missing.ok)
        assertTrue(missing.state.cart.isEmpty())
        assertEquals(listOf("M", "L", "XL"), missing.choices["사이즈"])
        assertTrue(missing.result, missing.result.contains("사이즈를 골라야 담을 수 있다"))
        assertEquals(listOf("M", "L", "XL"), missing.state.known.first().options["사이즈"]) // 다음 스키마에 값이 생긴다
        assertFalse(add(home, "p07002", 1, mapOf("사이즈" to "3XL")).ok)
    }

    @Test
    fun cartEdit_quantityAndRemove() = runTest {
        var s = add(home, "p07001").state
        s = exec.execute(Action.CartQuantity("c1", 4), s).state
        assertEquals(4, s.cart.single().qty)
        s = exec.execute(Action.CartRemove("c1"), s).state
        assertTrue(s.cart.isEmpty())
    }

    @Test
    fun checkoutThenPlace_sendsWholeCartAndClearsIt() = runTest {
        var s = add(home, "p07002", 2, mapOf("사이즈" to "XL")).state
        s = add(s, "p07001").state
        assertFalse(exec.execute(Action.Place, s).ok) // 확인 단계를 거쳐야 한다
        s = exec.execute(Action.Checkout, s).state
        assertEquals(Screen.Confirm, s.screen)
        val done = exec.execute(Action.Place, s)
        assertEquals(Screen.Done, done.state.screen)
        assertTrue(done.state.cart.isEmpty())
        assertEquals(2, done.state.orderIds.size)
        val sent = api.orders.single().items
        assertEquals(listOf("p07002" to mapOf("사이즈" to "XL"), "p07001" to emptyMap()), sent.map { it.productId to it.options })
        assertEquals(listOf(2, 1), sent.map { it.quantity })
    }

    @Test
    fun place_rejectedLine_keepsCartAndMarksIt() = runTest {
        api.orderResult = ApiResult.Http(409, "품절된 상품이다", OrderErrorDetailDto(message = "품절된 상품이다", index = 1, productId = "p07001"))
        var s = add(home, "p07002", 1, mapOf("사이즈" to "L")).state
        s = add(s, "p07001").state
        s = exec.execute(Action.Checkout, s).state
        val out = exec.execute(Action.Place, s)
        assertFalse(out.ok)
        assertEquals(Screen.Cart, out.state.screen)
        assertEquals("c2", out.state.cartProblem)
        assertEquals(2, out.state.cart.size)
    }

    @Test
    fun cancel_onlyFromConfirm_backToCart() = runTest {
        val s = add(home, "p07001").state
        assertFalse(exec.execute(Action.Cancel, s).ok)
        val confirm = exec.execute(Action.Checkout, s).state.copy(screen = Screen.Listening, returnTo = Screen.Confirm)
        assertEquals(Screen.Cart, exec.execute(Action.Cancel, confirm).state.screen)
    }

    @Test
    fun home_keepsCart_clearsSession() = runTest {
        var s = exec.execute(Action.Search("무릎"), home).state
        s = add(s, "p07001").state
        val out = exec.execute(Action.Home, s)
        assertEquals(1, out.state.cart.size)
        assertTrue(out.state.known.isEmpty())
        assertNull(out.state.current)
    }

    @Test
    fun showHistory_listsPastOrders() = runTest {
        api.history = ApiResult.Ok(listOf(OrderDto("M-1", "u001", "p01001", "햅쌀 10kg", 1, emptyMap(), 34900, "2026-09-12T10:00:00+09:00")))
        val out = exec.execute(Action.ShowHistory, home)
        assertEquals(Screen.History, out.state.screen)
        assertEquals("2026-09-12", out.state.pastOrders.single().date)
        assertEquals("p01001", out.state.known.single().id)
        assertTrue(exec.execute(Action.History, home).result.contains("2026-09-12 p01001 햅쌀 10kg 1개"))
    }

    @Test
    fun offline_isReported() = runTest {
        api.offline = true
        val out = exec.execute(Action.Search("쌀"), home)
        assertTrue(out.offline)
        assertTrue(out.state.offline)
    }
}
