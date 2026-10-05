package com.agentpjt.shop.agent

import com.agentpjt.shop.TestData
import com.agentpjt.shop.shop.CartLine
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.toSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

    private val candidates = listOf("p01", "p02").map { TestData.compact(it, it, 1000).toSummary() }
    private val known = listOf(KnownProduct("p07002", "무릎 보호대", mapOf("사이즈" to listOf("M", "L"))), KnownProduct("p01001", "햅쌀"))
    private val cart = listOf(CartLine("c1", "p01001", "햅쌀", 34900, 0, emptyMap(), 1, 0))
    private val ALWAYS = setOf(
        Action.Kind.SEARCH, Action.Kind.HISTORY, Action.Kind.SHOW_CART, Action.Kind.SHOW_HISTORY,
        Action.Kind.HOME, Action.Kind.ASK, Action.Kind.ANSWER,
    )

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun alwaysAvailable_onEveryScreen() {
        Screen.entries.forEach { screen ->
            val s = ShopState(screen = screen, candidates = candidates, known = known, cart = cart)
            assertTrue(screen.name, Actions.available(s).containsAll(ALWAYS))
        }
    }

    @Test
    fun emptySession_hasOnlyAlwaysActions() {
        assertEquals(ALWAYS, Actions.available(ShopState()).toSet())
    }

    @Test
    fun placeAndCancel_onlyWhileConfirmingANonEmptyCart() {
        val s = ShopState(screen = Screen.Cart, cart = cart)
        assertFalse(Action.Kind.PLACE in Actions.available(s))
        assertTrue(Action.Kind.CHECKOUT in Actions.available(s))
        val confirm = s.copy(screen = Screen.Listening, returnTo = Screen.Confirm)
        assertTrue(Actions.available(confirm).containsAll(listOf(Action.Kind.PLACE, Action.Kind.CANCEL)))
        assertFalse(Action.Kind.CHECKOUT in Actions.available(confirm))
        assertFalse(Action.Kind.PLACE in Actions.available(confirm.copy(cart = emptyList())))
    }

    @Test
    fun cartEditing_needsLines_andAddingNeedsKnownProducts() {
        val none = Actions.available(ShopState(candidates = candidates))
        listOf(Action.Kind.CART_ADD, Action.Kind.CART_REMOVE, Action.Kind.CART_QUANTITY, Action.Kind.CHECKOUT).forEach { assertFalse(it.name, it in none) }
        assertTrue(Action.Kind.SHOW in none)
    }

    @Test
    fun schema_limitsValuesToState_andOptionsAreOptional() {
        val s = ShopState(candidates = candidates, known = known, cart = cart)
        val branches = Actions.schema(s)["anyOf"]!!.jsonArray.map { it.jsonObject }
        fun of(action: String) = branches.filter { it.props()["action"]!!.jsonObject["const"]!!.jsonPrimitive.content == action }

        val adds = of("cart_add")
        assertEquals(listOf("p07002", "p01001"), adds.map { it.props()["productId"]!!.jsonObject["const"]!!.jsonPrimitive.content })
        val knee = adds.first()
        assertEquals(listOf("M", "L"), knee.props()["options"]!!.jsonObject["properties"]!!.jsonObject["사이즈"]!!.enumValues())
        assertEquals(listOf("action", "productId", "quantity"), knee["required"]!!.jsonArray.map { it.jsonPrimitive.content }) // 옵션은 선택, 수량은 null 가능
        assertFalse("options" in adds[1].props()) // 옵션을 모르는 상품
        assertEquals(listOf("c1"), of("cart_remove").single().props()["lineId"]!!.enumValues())
        assertEquals(listOf("p01", "p02"), of("show").single().props()["ids"]!!.jsonObject["items"]!!.enumValues())
    }

    @Test
    fun searchCategory_onlyFromCategoriesSeenInLatestResults() {
        fun searchProps(s: ShopState) = Actions.schema(s)["anyOf"]!!.jsonArray.map { it.jsonObject }
            .single { it.props()["action"]!!.jsonObject["const"]!!.jsonPrimitive.content == "search" }.props()
        assertFalse("category" in searchProps(ShopState())) // 분류를 모르면 없다
        // 결과를 보기 전: 대분류만(지시문에 이름이 있다)
        assertEquals(listOf("식품·신선", "실버·보조용품"), searchProps(ShopState(catalog = TestData.catalog))["category"]!!.enumValues())
        // 결과를 본 뒤: 대분류 + 결과에 나온 중분류
        val seen = ShopState(catalog = TestData.catalog, candidates = candidates) // compact() 의 sub 는 "보호대"
        assertEquals(listOf("식품·신선", "실버·보조용품", "보호대·지지대"), searchProps(seen)["category"]!!.enumValues())
        assertEquals("보호대·지지대", (Actions.parse(json("""{"action":"search","query":"무릎","category":"보호대·지지대"}"""), seen) as Action.Search).category)
        assertNull((Actions.parse(json("""{"action":"search","query":"김치","category":"반찬"}"""), seen) as Action.Search).category)
    }

    @Test
    fun conditionSlots_areRequiredButNullable_andHomeIsGoneAfterObserving() {
        val s = ShopState(candidates = candidates, known = known)
        val search = Actions.schema(s)["anyOf"]!!.jsonArray.map { it.jsonObject }
            .single { it.props()["action"]!!.jsonObject["const"]!!.jsonPrimitive.content == "search" }
        assertEquals(listOf("action", "query", "budget", "sort", "gift"), search["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val parsed = Actions.parse(json("""{"action":"search","query":"라디오","budget":"오만 원 안쪽","sort":null,"gift":null}"""), s) as Action.Search
        assertEquals(50000, parsed.maxPrice)
        assertNull(parsed.sort)
        assertTrue(Action.Kind.HOME in Actions.available(s))
        assertFalse(Action.Kind.HOME in Actions.available(s, observed = true))
    }

    @Test
    fun parse_rejectsValuesOutsideState() {
        val s = ShopState(candidates = candidates, known = known, cart = cart)
        assertEquals(Action.CartAdd("p07002", 2, mapOf("사이즈" to "L")), Actions.parse(json("""{"action":"cart_add","productId":"p07002","quantity":2,"options":{"사이즈":"L"}}"""), s))
        assertNull(Actions.parse(json("""{"action":"cart_add","productId":"p07002","options":{"사이즈":"XXL"}}"""), s))
        assertNull(Actions.parse(json("""{"action":"cart_add","productId":"p99999"}"""), s))
        assertNull(Actions.parse(json("""{"action":"cart_remove","lineId":"c9"}"""), s))
        assertNull(Actions.parse(json("""{"action":"show","ids":["p99"],"label":"x"}"""), s))
        assertNull(Actions.parse(json("""{"action":"place"}"""), s)) // 확인 단계가 아니다
        assertNull(Actions.parse(json("""{"action":"fly"}"""), s))
    }

    private fun JsonObject.props() = this["properties"]!!.jsonObject
    private fun JsonElement.enumValues() = jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
}
