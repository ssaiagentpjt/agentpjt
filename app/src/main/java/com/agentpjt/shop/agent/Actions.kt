package com.agentpjt.shop.agent

import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.parseWon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 모델이 한 단계에 고르는 행동. [Kind.observes] 인 행동은 결과를 모델에 돌려주고 루프를 잇고,
 * 나머지는 턴을 끝내고 화면에 넘긴다(AgentLoop). 장바구니 편집이 관찰 행동이라 한 발화 안에서
 * "담기 → 담기 → 결제 확인"처럼 모델이 이어 갈 수 있다.
 */
sealed interface Action {
    val kind: Kind

    data class Search(
        val query: String,
        val maxPrice: Int? = null,
        val minPrice: Int? = null,
        val sort: String? = null,
        val priceTier: String? = null,
        val gift: Boolean? = null,
        val audience: String? = null,
        /** 매장 분류 이름(대분류 또는 중분류). 막연한 말을 실제로 있는 분류로 좁힌다 */
        val category: String? = null,
    ) : Action { override val kind get() = Kind.SEARCH }

    data class Info(val productId: String) : Action { override val kind get() = Kind.INFO }
    data object History : Action { override val kind get() = Kind.HISTORY }
    data class CartAdd(val productId: String, val quantity: Int? = null, val options: Map<String, String> = emptyMap()) : Action {
        override val kind get() = Kind.CART_ADD
    }
    data class CartRemove(val lineId: String) : Action { override val kind get() = Kind.CART_REMOVE }
    data class CartQuantity(val lineId: String, val count: Int) : Action { override val kind get() = Kind.CART_QUANTITY }
    data class Show(val ids: List<String>, val label: String) : Action { override val kind get() = Kind.SHOW }
    data class Open(val productId: String) : Action { override val kind get() = Kind.OPEN }
    data object ShowCart : Action { override val kind get() = Kind.SHOW_CART }
    data object ShowHistory : Action { override val kind get() = Kind.SHOW_HISTORY }
    data object Checkout : Action { override val kind get() = Kind.CHECKOUT }
    data object Place : Action { override val kind get() = Kind.PLACE }
    data object Cancel : Action { override val kind get() = Kind.CANCEL }
    data object Home : Action { override val kind get() = Kind.HOME }
    data class Ask(val question: String) : Action { override val kind get() = Kind.ASK }
    data class Answer(val text: String) : Action { override val kind get() = Kind.ANSWER }

    enum class Kind(val wire: String, val observes: Boolean) {
        SEARCH("search", true), INFO("info", true), HISTORY("history", true),
        CART_ADD("cart_add", true), CART_REMOVE("cart_remove", true), CART_QUANTITY("cart_quantity", true),
        SHOW("show", false), OPEN("open", false), SHOW_CART("show_cart", false), SHOW_HISTORY("show_history", false),
        CHECKOUT("checkout", false), PLACE("place", false), CANCEL("cancel", false), HOME("home", false),
        ASK("ask", false), ANSWER("answer", false),
    }
}

/**
 * 서버가 받는 정렬 값. 스키마 enum 으로 묶어서 모델이 이 밖의 값을 낼 수 없다.
 * 서버의 priceTier·audience·minPrice 거르기는 모델에게 주지 않는다: 말하지 않았는데도 채워 맞는 상품을 걸렀다(PC 벤치)
 */
val SORTS = listOf("relevance", "sales", "rating", "price_asc", "price_desc", "discount", "unit_price")

/**
 * 상태에서 행동의 가능 여부와 값의 범위를 계산한다. 빼는 것은 사실상 불가능하거나 위험한 것뿐이다 —
 * 화면별 허용 목록은 두지 않는다. 사용자 말은 보지 않는다.
 * 행동은 대상을 id(아는 상품·장바구니 줄)로 명시해서 받는다. 화면에 보이지 않는 대상에 몰래 적용되지 않는다.
 */
object Actions {

    /**
     * [observed]: 이번 발화에서 이미 관찰 행동(search 등)의 결과를 받았다. 그 뒤에 home(세션을 끝냄)을 고르면
     * 어르신의 요청을 버리게 된다 — 실기기에서 검색 0건 뒤 home 으로 빠졌다(eval5 1·3번). 그래서 그때는 뺀다.
     */
    fun available(s: ShopState, observed: Boolean = false): List<Action.Kind> = Action.Kind.entries.filter { k ->
        val confirming = s.effectiveScreen == Screen.Confirm && s.cart.isNotEmpty()
        when (k) {
            Action.Kind.HOME -> !observed
            Action.Kind.SEARCH, Action.Kind.HISTORY, Action.Kind.SHOW_CART, Action.Kind.SHOW_HISTORY,
            Action.Kind.ASK, Action.Kind.ANSWER -> true
            Action.Kind.INFO, Action.Kind.OPEN, Action.Kind.CART_ADD -> s.known.isNotEmpty()
            Action.Kind.SHOW -> s.candidates.isNotEmpty()
            Action.Kind.CART_REMOVE, Action.Kind.CART_QUANTITY -> s.cart.isNotEmpty()
            Action.Kind.CHECKOUT -> s.cart.isNotEmpty() && !confirming
            // 결제 확정·취소는 주문 확인 단계(checkout 으로만 열린다)에서만 있다. 주문 안전을 말 해석이 아니라 상태로 지킨다
            Action.Kind.PLACE, Action.Kind.CANCEL -> confirming
        }
    }

    /** 지금 상태에서 고를 수 있는 행동만 담은 JSON 스키마(anyOf). ResponseFormat 으로 출력을 이 안에 묶는다 */
    fun schema(s: ShopState, observed: Boolean = false): JsonObject = buildJsonObject {
        put("anyOf", buildJsonArray { available(s, observed).flatMap { branches(it, s) }.forEach { add(it) } })
    }

    /**
     * 검색을 좁힐 수 있는 분류: 최근 검색 결과에 실제로 나온 상품들의 대분류·중분류뿐이다.
     * 첫 검색에서 모델이 분류를 짐작으로 채워 맞는 상품을 걸렀다(eval5: "음식"+음료·차 0건, "돌잔치 선물"+기초화장품)
     */
    fun observedCategories(s: ShopState): List<String> =
        s.candidates.flatMap { s.catalog.parentsOf(it.sub) }.distinct()

    /** 검색을 좁힐 수 있는 분류: 대분류 16개(지시문에 이름이 있다) + 앞 결과에 나온 중분류 */
    fun searchCategories(s: ShopState): List<String> = (s.catalog.mainNames + observedCategories(s)).distinct()

    private fun branches(k: Action.Kind, s: ShopState): List<JsonObject> = when (k) {
        Action.Kind.SEARCH -> listOf(obj(k, required = listOf("query", "budget", "sort", "gift")) {
            put("query", str())
            put("budget", nullable(str(max = 30)))
            put("sort", nullable(enumOf(SORTS)))
            put("gift", nullable(buildJsonObject { put("type", "boolean") }))
            searchCategories(s).takeIf { it.isNotEmpty() }?.let { put("category", enumOf(it)) }
        })
        Action.Kind.INFO, Action.Kind.OPEN -> listOf(obj(k, required = listOf("productId")) { put("productId", enumOf(s.known.map { it.id })) })
        // 상품마다 가지를 나눠 옵션 값이 그 상품의 실제 값만 되게 한다. 옵션은 선택 사항 —
        // 어르신이 말하지 않은 옵션을 모델이 지어 채우지 않게, 빠지면 실행기가 고를 수 있는 값을 돌려준다
        Action.Kind.CART_ADD -> s.known.map { p ->
            obj(k, required = listOf("productId", "quantity")) {
                put("productId", buildJsonObject { put("const", p.id) })
                put("quantity", nullable(int(1, 9)))
                if (p.options.isNotEmpty()) {
                    putJsonObject("options") {
                        put("type", "object")
                        putJsonObject("properties") { p.options.forEach { (axis, values) -> put(axis, enumOf(values)) } }
                        put("additionalProperties", false)
                    }
                }
            }
        }
        Action.Kind.CART_REMOVE -> listOf(obj(k, required = listOf("lineId")) { put("lineId", enumOf(s.cart.map { it.lineId })) })
        Action.Kind.CART_QUANTITY -> listOf(obj(k, required = listOf("lineId", "count")) {
            put("lineId", enumOf(s.cart.map { it.lineId }))
            put("count", int(1, 9))
        })
        Action.Kind.SHOW -> listOf(obj(k, required = listOf("ids", "label")) {
            put("ids", buildJsonObject {
                put("type", "array"); put("minItems", 1); put("maxItems", 3)
                put("items", enumOf(s.candidates.map { it.id }))
            })
            put("label", str(max = 30)) // 넘침 방지용 안전 상한. 뜻은 지시문이 알려 준다
        })
        Action.Kind.ASK -> listOf(obj(k, required = listOf("question")) { put("question", str(max = 200)) })
        Action.Kind.ANSWER -> listOf(obj(k, required = listOf("text")) { put("text", str(max = 200)) })
        Action.Kind.HISTORY, Action.Kind.SHOW_CART, Action.Kind.SHOW_HISTORY, Action.Kind.CHECKOUT,
        Action.Kind.PLACE, Action.Kind.CANCEL, Action.Kind.HOME -> listOf(obj(k) {})
    }

    /**
     * 모델 출력 → 행동. 출력은 스키마로 묶여 있지만, 주문처럼 되돌리기 어려운 일이 걸려 있어 같은 조건을 한 번 더 본다.
     * 맞지 않으면 null(엔진 이상으로 다룬다).
     */
    fun parse(json: JsonObject, s: ShopState, observed: Boolean = false): Action? {
        val kind = Action.Kind.entries.firstOrNull { it.wire == json.string("action") } ?: return null
        if (kind !in available(s, observed)) return null
        fun knownId(key: String) = json.string(key)?.takeIf { id -> s.known.any { it.id == id } }
        fun lineId() = json.string("lineId")?.takeIf { id -> s.cart.any { it.lineId == id } }
        return when (kind) {
            Action.Kind.SEARCH -> Action.Search(
                query = json.string("query")?.takeIf { it.isNotBlank() } ?: return null,
                maxPrice = json.string("budget")?.let(::parseWon),
                sort = json.string("sort")?.takeIf { it in SORTS },
                gift = (json["gift"] as? JsonPrimitive)?.booleanOrNull,
                category = json.string("category")?.takeIf { it in searchCategories(s) },
            )
            Action.Kind.INFO -> knownId("productId")?.let { Action.Info(it) }
            Action.Kind.OPEN -> knownId("productId")?.let { Action.Open(it) }
            Action.Kind.HISTORY -> Action.History
            Action.Kind.CART_ADD -> {
                val id = knownId("productId") ?: return null
                val axes = s.known.first { it.id == id }.options
                val options = (json["options"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: return null }.orEmpty()
                // 알고 있는 축이면 값이 그 안에 있어야 한다. 축을 모르는 상품은 실행기가 서버 상세로 검사한다
                if (axes.isNotEmpty() && options.any { (axis, v) -> axes[axis]?.contains(v) != true }) return null
                Action.CartAdd(id, json.int("quantity")?.takeIf { it in 1..9 }, options)
            }
            Action.Kind.CART_REMOVE -> lineId()?.let { Action.CartRemove(it) }
            Action.Kind.CART_QUANTITY -> {
                val line = lineId() ?: return null
                json.int("count")?.takeIf { it in 1..9 }?.let { Action.CartQuantity(line, it) }
            }
            Action.Kind.SHOW -> {
                val ids = (json["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.distinct().orEmpty()
                if (ids.isEmpty() || ids.size > 3 || ids.any { id -> s.candidates.none { it.id == id } }) return null
                Action.Show(ids, json.string("label").orEmpty())
            }
            Action.Kind.SHOW_CART -> Action.ShowCart
            Action.Kind.SHOW_HISTORY -> Action.ShowHistory
            Action.Kind.CHECKOUT -> Action.Checkout
            Action.Kind.PLACE -> Action.Place
            Action.Kind.CANCEL -> Action.Cancel
            Action.Kind.HOME -> Action.Home
            Action.Kind.ASK -> json.string("question")?.takeIf { it.isNotBlank() }?.let { Action.Ask(it) }
            Action.Kind.ANSWER -> json.string("text")?.takeIf { it.isNotBlank() }?.let { Action.Answer(it) }
        }
    }

    private fun obj(k: Action.Kind, required: List<String> = emptyList(), props: JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") { put("const", k.wire) }
                props()
            }
            putJsonArray("required") { add(JsonPrimitive("action")); required.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }

    private fun str(max: Int? = null) = buildJsonObject {
        put("type", "string")
        put("minLength", 1)
        if (max != null) put("maxLength", max)
    }

    private fun int(min: Int? = null, max: Int? = null) = buildJsonObject {
        put("type", "integer")
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

    /** 값 또는 null. 필수로 두면 모델이 그 칸을 반드시 거치고, 말하지 않았으면 null 을 쓴다 */
    private fun nullable(schema: JsonObject) = buildJsonObject {
        put("anyOf", buildJsonArray { add(schema); add(buildJsonObject { put("type", "null") }) })
    }

    private fun enumOf(values: List<String>) = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
