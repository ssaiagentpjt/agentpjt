package com.agentpjt.shop.agent

import com.agentpjt.shop.shop.Catalog
import com.agentpjt.shop.shop.Product
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.formatWon
import com.agentpjt.shop.shop.readWon

/**
 * 모델이 부른 도구를 실행한다. 순수 함수 — 상태를 받아 새 상태와 모델에 돌려줄 결과를 낸다.
 * 여기서 하는 검사는 확실한 것뿐이다: id 존재, 숫자 범위, 화면 상태. 사용자 말은 보지 않는다.
 */
class ToolExecutor(private val catalog: Catalog) {

    data class Outcome(
        val state: ShopState,
        /** 모델에게 돌려줄 결과. LiteRT-LM 이 JSON 으로 바꿔 넘긴다. */
        val response: Map<String, Any?>,
        /** 화면이 바뀌었으면 그 화면 대본이 읽기를 맡는다. */
        val navigated: Boolean,
    )

    fun execute(name: String, args: Map<String, Any?>, s: ShopState): Outcome {
        // 도구 이름은 런타임 설정에 따라 camelCase 또는 snake_case 로 온다
        return when (name.replace("_", "").lowercase()) {
            "getproducts" -> getProducts(args, s)
            "showproducts" -> showProducts(args, s)
            "openproduct" -> openProduct(args, s)
            "setquantity" -> setQuantity(args, s)
            "requestorder" -> requestOrder(args, s)
            "placeorder" -> placeOrder(s)
            "gohome" -> Outcome(ShopState(ai = s.ai, thinkingMode = s.thinkingMode), ok("처음 화면으로 갔다"), navigated = true)
            else -> error(s, "없는 도구: $name")
        }
    }

    private fun getProducts(args: Map<String, Any?>, s: ShopState): Outcome {
        val category = args.str("category") ?: return error(s, "category 가 필요하다")
        if (category !in catalog.categories()) {
            return error(s, "없는 카테고리: $category. 가능한 카테고리: ${catalog.categories().joinToString()}")
        }
        val maxPrice = args.int("maxPrice")
        val items = catalog.products(category, maxPrice).map { it.toToolJson() }
        return Outcome(s, mapOf("category" to category, "maxPrice" to maxPrice, "products" to items), navigated = false)
    }

    private fun showProducts(args: Map<String, Any?>, s: ShopState): Outcome {
        val ids = args.strList("productIds")
        if (ids.isEmpty()) return error(s, "productIds 가 비었다")
        val unknown = ids.filter { catalog.byId(it) == null }
        if (unknown.isNotEmpty()) return error(s, "없는 상품 id: ${unknown.joinToString()}. getProducts 결과의 id 를 써라")
        val products = ids.distinct().take(3).mapNotNull { catalog.byId(it) }
        val label = args.str("label")?.takeIf { it.isNotBlank() } ?: "추천 상품"
        val next = s.copy(screen = Screen.Results, shown = products, label = label)
        return Outcome(next, ok("추천 화면에 ${products.size}개를 띄웠다: " + products.mapIndexed { i, p -> "${i + 1}) ${p.id}" }.joinToString()), navigated = true)
    }

    private fun openProduct(args: Map<String, Any?>, s: ShopState): Outcome {
        val p = args.str("productId")?.let(catalog::byId) ?: return error(s, "없는 상품 id: ${args["productId"]}")
        return Outcome(s.copy(screen = Screen.Detail, current = p, qty = 1), ok("${p.id} 상세 화면을 열었다"), navigated = true)
    }

    private fun setQuantity(args: Map<String, Any?>, s: ShopState): Outcome {
        val count = args.int("count") ?: return error(s, "count 가 필요하다")
        if (count !in 1..9) return error(s, "수량은 1에서 9 사이다")
        if (s.current == null || s.screen !in setOf(Screen.Detail, Screen.Confirm, Screen.Listening)) {
            return error(s, "보고 있는 상품이 없다. 먼저 openProduct 를 써라")
        }
        return Outcome(s.copy(qty = count), ok("수량을 ${count}개로 바꿨다"), navigated = false)
    }

    private fun requestOrder(args: Map<String, Any?>, s: ShopState): Outcome {
        val p = args.str("productId")?.let(catalog::byId) ?: return error(s, "없는 상품 id: ${args["productId"]}")
        val qty = args.int("quantity") ?: 1
        if (qty !in 1..9) return error(s, "수량은 1에서 9 사이다")
        return Outcome(s.copy(screen = Screen.Confirm, current = p, qty = qty), ok("주문 확인 화면을 열었다. 사용자의 동의를 기다린다"), navigated = true)
    }

    // 주문 안전은 말 해석이 아니라 상태로 지킨다: 확인 화면(대본으로 금액을 읽어 준 뒤)에서만 확정된다.
    private fun placeOrder(s: ShopState): Outcome {
        val onConfirm = s.screen == Screen.Confirm || (s.screen == Screen.Listening && s.returnTo == Screen.Confirm)
        if (!onConfirm || s.current == null) return error(s, "주문 확인 화면이 아니다. 먼저 requestOrder 를 써라")
        val id = "M-20261003-" + (1000..9999).random()
        return Outcome(s.copy(screen = Screen.Done, orderId = id), ok("주문을 확정했다. 주문번호 $id"), navigated = true)
    }

    private fun ok(message: String) = mapOf("ok" to true, "message" to message)
    private fun error(s: ShopState, message: String) = Outcome(s, mapOf("ok" to false, "error" to message), navigated = false)
}

/** 모델에게 보여 주는 상품. 가격은 읽는 말도 같이 줘서 모델이 숫자를 직접 읽지 않게 한다. */
internal fun Product.toToolJson(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "price" to formatWon(price),
    "priceSpoken" to readWon(price),
    "shipping" to if (shippingFee > 0) formatWon(shippingFee) else "무료",
    "arrive" to arriveLabel,
    "rocket" to isRocket,
)

// 인자 키는 런타임 설정에 따라 camelCase 또는 snake_case 로 온다. 숫자는 JSON 을 거쳐 Double/Long 으로 올 수 있다.
private fun Map<String, Any?>.raw(key: String): Any? =
    this[key] ?: this[key.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }]

private fun Map<String, Any?>.str(key: String): String? = raw(key)?.toString()?.trim()

private fun Map<String, Any?>.int(key: String): Int? = when (val v = raw(key)) {
    is Number -> v.toInt()
    is String -> v.trim().toDoubleOrNull()?.toInt()
    else -> null
}

private fun Map<String, Any?>.strList(key: String): List<String> = when (val v = raw(key)) {
    is List<*> -> v.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
    is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}
