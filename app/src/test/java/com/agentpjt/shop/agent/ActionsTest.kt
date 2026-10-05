package com.agentpjt.shop.agent

import com.agentpjt.shop.TestData
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.toDetail
import com.agentpjt.shop.shop.toSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가능 행동은 "불가능하거나 위험한 것만 빠진다" — 화면별 허용 목록이 아니다. */
class ActionsTest {

    private val kneeDetail = TestData.knee.toDetail() // 사이즈 M/L/XL, 3XL 품절
    private val candidates = listOf("p01", "p02").map { TestData.compact(it, it, 1000).toSummary() }
    private val ALWAYS = setOf(Action.Kind.SEARCH, Action.Kind.HISTORY, Action.Kind.HOME, Action.Kind.ASK, Action.Kind.ANSWER)

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun alwaysAvailable_onEveryScreen() {
        Screen.entries.forEach { screen ->
            val s = ShopState(screen = screen, current = kneeDetail, candidates = candidates)
            assertTrue(screen.name, Actions.available(s).containsAll(ALWAYS))
        }
    }

    @Test
    fun emptySession_hasOnlyAlwaysActions() {
        assertEquals(ALWAYS, Actions.available(ShopState()).toSet())
    }

    @Test
    fun placeAndCancel_onlyWhileConfirming() {
        val detail = ShopState(screen = Screen.Detail, current = kneeDetail)
        assertFalse(Action.Kind.PLACE in Actions.available(detail))
        val confirm = detail.copy(screen = Screen.Listening, returnTo = Screen.Confirm)
        assertTrue(Actions.available(confirm).containsAll(listOf(Action.Kind.PLACE, Action.Kind.CANCEL)))
        assertFalse(Action.Kind.ORDER in Actions.available(confirm))
    }

    @Test
    fun productActions_needAProduct() {
        val none = Actions.available(ShopState(screen = Screen.Results, candidates = candidates))
        listOf(Action.Kind.OPTION, Action.Kind.QUANTITY, Action.Kind.ORDER).forEach { assertFalse(it.name, it in none) }
        assertTrue(Action.Kind.SHOW in none)
    }

    @Test
    fun schema_limitsValuesToState() {
        val s = ShopState(screen = Screen.Detail, current = kneeDetail, candidates = candidates, known = listOf(KnownProduct("p07002", "무릎 보호대")))
        val branches = Actions.schema(s)["anyOf"]!!.jsonArray.map { it.jsonObject }
        fun branch(action: String) = branches.single { it.props()["action"]!!.jsonObject["const"]!!.jsonPrimitive.content == action }.props()

        assertEquals(listOf("M", "L", "XL"), branch("option")["value"]!!.enumValues()) // 품절 3XL 은 없다
        assertEquals(listOf("p01", "p02"), branch("show")["ids"]!!.jsonObject["items"]!!.enumValues())
        assertEquals(listOf("p07002"), branch("open")["productId"]!!.enumValues())
    }

    @Test
    fun parse_rejectsValuesOutsideState() {
        val s = ShopState(screen = Screen.Detail, current = kneeDetail, candidates = candidates)
        assertEquals(Action.Option("사이즈", "L"), Actions.parse(json("""{"action":"option","name":"사이즈","value":"L"}"""), s))
        assertNull(Actions.parse(json("""{"action":"option","name":"사이즈","value":"3XL"}"""), s))
        assertNull(Actions.parse(json("""{"action":"show","ids":["p99"],"label":"x"}"""), s))
        assertNull(Actions.parse(json("""{"action":"place"}"""), s)) // 확인 단계가 아니다
        assertNull(Actions.parse(json("""{"action":"fly"}"""), s))
    }

    private fun JsonObject.props() = this["properties"]!!.jsonObject
    private fun kotlinx.serialization.json.JsonElement.enumValues() = jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
}
