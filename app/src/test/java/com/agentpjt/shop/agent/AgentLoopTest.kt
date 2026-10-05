package com.agentpjt.shop.agent

import com.agentpjt.shop.FakeShopApi
import com.agentpjt.shop.TestData
import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.SearchDto
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 루프의 성질을 본다. 모델은 정해 둔 출력을 차례로 내는 가짜다 — 무엇을 고를지는 여기서 검사하지 않는다. */
class AgentLoopTest {

    private class FakeDecider(vararg outputs: String) : Decider {
        private val queue = ArrayDeque(outputs.map { Json.parseToJsonElement(it).jsonObject })
        val messages = mutableListOf<String>()
        val schemas = mutableListOf<JsonObject>()
        var resets = 0
        override suspend fun reset() { resets++ }
        override suspend fun next(message: String, schema: JsonObject): JsonObject {
            messages += message; schemas += schema
            return queue.removeFirst()
        }
        override fun tokenCount() = 0
    }

    private val api = FakeShopApi().apply {
        searchResult = ApiResult.Ok(SearchDto("x", 2, listOf(TestData.compact("p01", "상품 하나", 1000), TestData.compact("p02", "상품 둘", 2000))))
        products["p07002"] = TestData.knee
    }

    private fun loop(d: Decider, maxSteps: Int = 4) = AgentLoop(d, ToolExecutor(api), maxSteps)
    private fun JsonObject.actions() = this["anyOf"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject["action"]!!.jsonObject["const"]!!.jsonPrimitive.content }

    @Test
    fun observation_feedsResultBack_andSchemaIsRecomputed() = runTest {
        val d = FakeDecider("""{"action":"search","query":"x"}""", """{"action":"show","ids":["p02"],"label":"찾은 것"}""")
        val steps = mutableListOf<String>()
        val turn = loop(d).handle("아무 말", ShopState(), onStep = { steps += it.doing })

        assertTrue(turn is AgentTurn.Moved)
        assertEquals(Screen.Results, turn.state.screen)
        assertEquals(listOf("p02"), turn.state.shown.map { it.id })
        assertTrue(d.messages[1], d.messages[1].startsWith("(앱) 'x' 검색 결과"))
        assertFalse("show" in d.schemas[0].actions()) // 후보가 없을 때는 show 가 없다
        assertTrue("show" in d.schemas[1].actions()) // 검색 뒤에 생긴다
        assertEquals(listOf("'x' 찾고 있어요", "다음 할 일을 고르는 중"), steps)
    }

    @Test
    fun askAndAnswer_endTheTurnWithoutChangingState() = runTest {
        val s = ShopState(screen = Screen.Listening, returnTo = Screen.Home)
        val turn = loop(FakeDecider("""{"action":"ask","question":"누구에게 보낼 거예요?"}""")).handle("뭐 하나 사 줘", s)
        assertEquals(AgentTurn.Said(s, "누구에게 보낼 거예요?"), turn)
    }

    @Test
    fun stepLimit_fails() = runTest {
        val d = FakeDecider(*Array(3) { """{"action":"search","query":"x"}""" })
        val turn = loop(d, maxSteps = 3).handle("아무 말", ShopState())
        assertEquals(AgentTurn.Reason.STEP_LIMIT, (turn as AgentTurn.Failed).reason)
        assertEquals(3, api.searches.size)
    }

    @Test
    fun offline_failsImmediately() = runTest {
        api.offline = true
        val turn = loop(FakeDecider("""{"action":"search","query":"x"}""")).handle("아무 말", ShopState())
        assertEquals(AgentTurn.Reason.OFFLINE, (turn as AgentTurn.Failed).reason)
    }

    @Test
    fun outputOutsideState_isEngineFailure_andNothingRuns() = runTest {
        val turn = loop(FakeDecider("""{"action":"place"}""")).handle("결제해", ShopState())
        assertEquals(AgentTurn.Reason.ENGINE, (turn as AgentTurn.Failed).reason)
        assertTrue(api.orders.isEmpty())
    }

    @Test
    fun cartEdits_chainInOneTurn_andCartIsReportedLive() = runTest {
        val d = FakeDecider(
            """{"action":"cart_add","productId":"p07002"}""", // 옵션이 빠졌다 → 결과로 고를 수 있는 값이 돌아간다
            """{"action":"cart_add","productId":"p07002","options":{"사이즈":"L"}}""",
            """{"action":"checkout"}""",
        )
        val carts = mutableListOf<Int>()
        val s = ShopState(known = listOf(KnownProduct("p07002", "무릎 보호대")))
        val turn = loop(d).handle("보호대 엘로 담고 주문해 줘", s, onCart = { carts += it.size })

        assertTrue(d.messages[1], d.messages[1].contains("사이즈를 골라야 담을 수 있다. 고를 수 있는 값: M, L, XL"))
        assertTrue(turn is AgentTurn.Moved)
        assertEquals(Screen.Confirm, turn.state.screen)
        assertEquals(listOf(1), carts)
        assertTrue("place" !in d.schemas[2].actions()) // 확인 단계 전에는 결제가 없다
    }

    @Test
    fun notes_arePrependedOnce() = runTest {
        val d = FakeDecider("""{"action":"ask","question":"뭘 드릴까요?"}""")
        val turn = loop(d).handle("음", ShopState(notes = listOf("앞의 요청은 어르신이 중단했다.")))
        assertTrue(d.messages[0], d.messages[0].startsWith("(앱) 앞의 요청은 어르신이 중단했다.\n"))
        assertTrue(turn.state.notes.isEmpty())
    }
}
