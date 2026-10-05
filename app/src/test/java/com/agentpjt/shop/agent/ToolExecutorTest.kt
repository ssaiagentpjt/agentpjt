package com.agentpjt.shop.agent

import com.agentpjt.shop.FakeShopApi
import com.agentpjt.shop.TestData
import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.OrderDto
import com.agentpjt.shop.api.SearchDto
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    private val api = FakeShopApi().apply {
        searchResult = ApiResult.Ok(SearchDto("무릎", 2, listOf(
            TestData.compact("p07002", "무릎 보호대 2개입", 16900, mapOf("사이즈" to listOf("M", "L", "XL"))),
            TestData.compact("p03005", "무릎 전용 핫팩 파스 12매", 15800),
        )))
        products["p07002"] = TestData.knee
        products["p07001"] = TestData.cane
    }
    private val exec = ToolExecutor(api)
    private val home = ShopState()

    private suspend fun detail(): ShopState = exec.execute(Action.Open("p07002"), home).state

    @Test
    fun search_cachesCandidates_remembersThem_andReturnsPlainResult() = runTest {
        val out = exec.execute(Action.Search("무릎", maxPrice = 20000, sort = "sales"), home)
        assertEquals(listOf("p07002", "p03005"), out.state.candidates.map { it.id })
        assertEquals(listOf("p07002", "p03005"), out.state.known.map { it.id })
        assertEquals(20000, api.searches.single().maxPrice)
        assertTrue(out.result, out.result.startsWith("(앱) '무릎' 검색 결과 2개 중 2개"))
        assertTrue(out.result, out.result.contains("p07002 무릎 보호대 2개입 · 만 육천구백 원"))
    }

    @Test
    fun show_movesToResults_onlyWithCandidates() = runTest {
        val searched = exec.execute(Action.Search("무릎"), home).state
        assertFalse(exec.execute(Action.Show(listOf("p99999"), "무릎"), searched).ok)
        val ok = exec.execute(Action.Show(listOf("p03005", "p07002"), "무릎"), searched)
        assertEquals(Screen.Results, ok.state.screen)
        assertEquals(listOf("p03005", "p07002"), ok.state.shown.map { it.id })
    }

    @Test
    fun open_resetsSelection_andRemembersProduct() = runTest {
        val out = exec.execute(Action.Open("p07002"), home.copy(selected = mapOf("색상" to "검정"), qty = 3))
        assertEquals(Screen.Detail, out.state.screen)
        assertTrue(out.state.selected.isEmpty())
        assertEquals(1, out.state.qty)
        assertEquals("p07002", out.state.known.first().id)
    }

    @Test
    fun option_rejectsSoldOutValue() = runTest {
        val s = detail()
        assertFalse(exec.execute(Action.Option("사이즈", "3XL"), s).ok)
        assertEquals(mapOf("사이즈" to "XL"), exec.execute(Action.Option("사이즈", "XL"), s).state.selected)
    }

    @Test
    fun order_withMissingOption_staysOnDetailWithChoices() = runTest {
        val out = exec.execute(Action.Order(), detail())
        assertFalse(out.ok)
        assertEquals(Screen.Detail, out.state.screen)
        assertEquals(listOf("M", "L", "XL"), out.choices["사이즈"])
    }

    @Test
    fun orderFlow_confirmThenPlace_sendsOptionsAndQty() = runTest {
        var s = exec.execute(Action.Option("사이즈", "XL"), detail()).state
        s = exec.execute(Action.Order(quantity = 2), s).state
        assertEquals(Screen.Confirm, s.screen)
        val done = exec.execute(Action.Place, s)
        assertEquals(Screen.Done, done.state.screen)
        assertEquals("M-20261005-1234", done.state.orderId)
        assertEquals(mapOf("사이즈" to "XL"), api.orders.single().options)
        assertEquals(2, api.orders.single().quantity)
    }

    @Test
    fun place_andCancel_onlyOnConfirm() = runTest {
        val s = detail()
        assertFalse(exec.execute(Action.Place, s).ok)
        assertFalse(exec.execute(Action.Cancel, s).ok)
        assertTrue(api.orders.isEmpty())
        val confirm = exec.execute(Action.Open("p07001"), home).state.copy(screen = Screen.Listening, returnTo = Screen.Confirm)
        assertEquals(Screen.Detail, exec.execute(Action.Cancel, confirm).state.screen)
        assertEquals(Screen.Done, exec.execute(Action.Place, confirm).state.screen)
    }

    @Test
    fun place_serverOptionError_goesBackToDetailWithChoices() = runTest {
        api.orderResult = FakeShopApi.optionError(409, mapOf("사이즈" to listOf("M", "L")))
        val s = detail().copy(screen = Screen.Confirm, selected = mapOf("사이즈" to "XL"))
        val out = exec.execute(Action.Place, s)
        assertEquals(Screen.Detail, out.state.screen)
        assertTrue(out.state.selected.isEmpty())
        assertEquals(listOf("M", "L"), out.choices["사이즈"])
    }

    @Test
    fun history_remembersPastProducts() = runTest {
        api.history = ApiResult.Ok(listOf(OrderDto("M-1", "u001", "p01001", "햅쌀 10kg", 1, emptyMap(), 34900, "2026-09-12T10:00:00+09:00")))
        val out = exec.execute(Action.History, home)
        assertTrue(out.result, out.result.contains("2026-09-12 p01001 햅쌀 10kg 1개"))
        assertEquals("p01001", out.state.known.single().id)
    }

    @Test
    fun offline_isReported() = runTest {
        api.offline = true
        val out = exec.execute(Action.Search("쌀"), home)
        assertTrue(out.offline)
        assertTrue(out.state.offline)
    }
}
