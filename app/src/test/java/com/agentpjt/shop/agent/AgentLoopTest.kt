package com.agentpjt.shop.agent

import com.agentpjt.shop.FakeShopApi
import com.agentpjt.shop.TestData
import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.SearchDto
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
        val progress = mutableListOf<String>()
        val turn = loop(d).handle("아무 말", ShopState()) { progress += it }

        assertTrue(turn is AgentTurn.Moved)
        assertEquals(Screen.Results, turn.state.screen)
        assertEquals(listOf("p02"), turn.state.shown.map { it.id })
        assertTrue(d.messages[1], d.messages[1].startsWith("(앱) 'x' 검색 결과"))
        assertFalse("show" in d.schemas[0].actions()) // 후보가 없을 때는 show 가 없다
        assertTrue("show" in d.schemas[1].actions()) // 검색 뒤에 생긴다
        assertEquals(listOf("'x' 찾고 있어요"), progress)
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
    fun orderWithMissingOption_needsOption() = runTest {
        val s = ToolExecutor(api).execute(Action.Open("p07002"), ShopState()).state.copy(screen = Screen.Listening, returnTo = Screen.Detail)
        val turn = loop(FakeDecider("""{"action":"order"}""")).handle("이걸로 살게", s)
        assertEquals(listOf("M", "L", "XL"), (turn as AgentTurn.NeedOption).choices["사이즈"])
    }
}
