package com.agentpjt.shop.agent

import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.BatchOrderInDto
import com.agentpjt.shop.api.OrderItemDto
import com.agentpjt.shop.api.SearchQuery
import com.agentpjt.shop.api.ShopApi
import com.agentpjt.shop.shop.CartLine
import com.agentpjt.shop.shop.DEMO_USER_ID
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.PastOrder
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.ShownList
import com.agentpjt.shop.shop.MAX_LISTS
import com.agentpjt.shop.shop.known
import com.agentpjt.shop.shop.nextLineId
import com.agentpjt.shop.shop.toDetail
import com.agentpjt.shop.shop.toSummary
import com.agentpjt.shop.shop.withObjectParticle

/**
 * 행동을 실행한다. 서버(ShopApi)를 부르고, 상태를 받아 새 상태와 결과를 낸다.
 * 여기서 하는 검사는 확실한 것뿐이다: 화면 상태, id·옵션 값 존재, 숫자 범위, 서버 응답. 사용자 말은 보지 않는다.
 * 터치 동작도 같은 실행기를 거친다(ShopViewModel) — 결제 경로가 하나라서 안전 검사도 한 곳에 있다.
 * 장바구니 파일 저장은 여기서 하지 않는다(ShopViewModel 이 상태의 cart 가 바뀔 때 저장한다).
 */
class ToolExecutor(private val api: ShopApi, private val userId: String = DEMO_USER_ID) {

    private companion object {
        /** 모델이 고를 후보 수. 5개로는 엉뚱한 결과뿐일 때 고를 것이 없었다. 늘리면 결과 메시지가 길어진다(실측해 조정) */
        const val SEARCH_LIMIT = 8
    }

    data class Outcome(
        val state: ShopState,
        val ok: Boolean,
        /** 관찰 행동이면 모델에게 돌려줄 결과 평문(ToolResults), 실패면 그 사유 */
        val result: String,
        /** 옵션을 골라야 해서 실패했을 때 고를 수 있는 값 */
        val choices: Map<String, List<String>> = emptyMap(),
        /** 서버에 닿지 못했다 */
        val offline: Boolean = false,
    )

    suspend fun execute(action: Action, s: ShopState): Outcome = when (action) {
        is Action.Search -> search(action, s)
        is Action.Info -> info(action.productId, s)
        Action.History -> history(s)
        is Action.CartAdd -> cartAdd(action, s)
        is Action.CartRemove -> cartRemove(action.lineId, s)
        is Action.CartQuantity -> cartQuantity(action, s)
        is Action.Show -> show(action, s)
        is Action.Open -> open(action.productId, s)
        Action.ShowCart -> ok(s.copy(screen = Screen.Cart), "장바구니 화면을 열었다")
        Action.ShowHistory -> showHistory(s)
        Action.Checkout -> checkout(s)
        Action.Place -> place(s)
        Action.Cancel -> cancel(s)
        Action.Home -> ok(s.freshSession(), "처음 화면으로 갔다")
        // 말하기는 상태를 바꾸지 않는다. 화면이 문장을 그린다
        is Action.Ask, is Action.Answer -> ok(s, "")
    }

    private suspend fun search(a: Action.Search, s: ShopState): Outcome {
        val filter = a.category?.let { s.catalog.filterOf(it) }
        // 검색어가 상황 태그 이름과 정확히 같으면 그 태그가 붙은 상품으로 거른다. 낱말 일치로 찾으면 "간식"이
        // 이름에 든 반려동물 간식이 걸렸다(PC 벤치 3번). 말뜻 해석이 아니라 어휘 일치다
        val need = a.query.trim().takeIf { it in s.catalog.needs }
        val query = SearchQuery(
            q = if (need != null) "" else a.query, minPrice = a.minPrice, maxPrice = a.maxPrice, priceTier = a.priceTier,
            sort = a.sort, gift = a.gift, audience = a.audience, main = filter?.first, mid = filter?.second, need = need,
            limit = SEARCH_LIMIT,
        )
        return when (val r = api.search(query)) {
            is ApiResult.Ok -> {
                val items = r.value.products.map { it.toSummary() }
                ok(s.copy(candidates = items, offline = false).remember(items.map { it.known() }),
                    ToolResults.search(a.query + (a.category?.let { " · 분류 $it" } ?: ""), r.value.total, items, s.catalog))
            }
            else -> apiFail(s, r)
        }
    }

    private fun show(a: Action.Show, s: ShopState): Outcome {
        val pool = (s.candidates + s.shown).associateBy { it.id }
        val ids = a.ids.distinct().take(3)
        val products = ids.mapNotNull { pool[it] }
        if (products.isEmpty() || products.size != ids.size) return fail(s, "최근 검색 결과에 없는 상품이다")
        val label = a.label.ifBlank { "추천 상품" }
        val lists = (listOf(ShownList(label, products.map { it.known() })) + s.lists).take(MAX_LISTS)
        return ok(s.copy(screen = Screen.Results, shown = products, label = label, lists = lists), "추천 화면에 ${products.size}개를 띄웠다")
    }

    private suspend fun info(id: String, s: ShopState): Outcome = when (val r = api.product(id)) {
        is ApiResult.Ok -> {
            val d = r.value.toDetail()
            ok(s.copy(offline = false).remember(listOf(d.known())), ToolResults.detail(d))
        }
        else -> apiFail(s, r)
    }

    private suspend fun open(id: String, s: ShopState): Outcome = when (val r = api.product(id)) {
        is ApiResult.Ok -> {
            val d = r.value.toDetail()
            ok(
                s.copy(screen = Screen.Detail, current = d, selected = emptyMap(), qty = 1, offline = false).remember(listOf(d.known())),
                "${d.name} 상세 화면을 열었다",
            )
        }
        else -> apiFail(s, r)
    }

    /** 담기. 가격·추가 금액·배송비를 서버 상세에서 받아 줄을 만든다. 옵션이 빠졌으면 담지 않고 고를 수 있는 값을 돌려준다 */
    private suspend fun cartAdd(a: Action.CartAdd, s: ShopState): Outcome {
        val d = when (val r = api.product(a.productId)) {
            is ApiResult.Ok -> r.value.toDetail()
            else -> return apiFail(s, r)
        }
        val base = s.copy(offline = false).remember(listOf(d.known())) // 축을 알게 됐으니 다음 담기 스키마에 값이 생긴다
        if (d.soldOut) return fail(base, "${d.name}는 품절이라 담을 수 없다")
        val unknownAxis = a.options.keys.filter { axis -> d.options.none { it.name == axis } }
        if (unknownAxis.isNotEmpty()) return fail(base, "${d.name}에는 ${unknownAxis.joinToString()} 옵션이 없다", d.options.associate { it.name to it.available })
        for (axis in d.options) {
            val wanted = a.options[axis.name] ?: continue
            val v = axis.values.firstOrNull { it.value == wanted } ?: return fail(base, "${axis.name}에 ${wanted}는 없다", mapOf(axis.name to axis.available))
            if (v.soldOut) return fail(base, "${axis.name} ${v.value}는 품절이다", mapOf(axis.name to axis.available))
        }
        val missing = d.missing(a.options)
        if (missing.isNotEmpty()) {
            val (axis, values) = missing.entries.first()
            return fail(base, "${d.name}는 ${withObjectParticle(axis)} 골라야 담을 수 있다. 고를 수 있는 값: ${values.joinToString(", ")}", missing)
        }
        val qty = a.quantity ?: 1
        val same = s.cart.firstOrNull { it.productId == d.id && it.options == a.options }
        val cart = if (same != null) {
            s.cart.map { if (it === same) it.copy(qty = (it.qty + qty).coerceAtMost(9)) else it }
        } else {
            val add = d.options.sumOf { axis -> axis.values.firstOrNull { it.value == a.options[axis.name] }?.priceAdd ?: 0 }
            s.cart + CartLine(s.cart.nextLineId(), d.id, d.name, d.price, add, a.options, qty.coerceIn(1, 9), d.shippingFee, d.image)
        }
        val line = cart.first { it.productId == d.id && it.options == a.options }
        return ok(base.copy(cart = cart, cartProblem = null), "(앱) 담았다: ${ToolResults.line(line)}. ${ToolResults.cart(cart)}")
    }

    private fun cartRemove(lineId: String, s: ShopState): Outcome {
        val line = s.cart.firstOrNull { it.lineId == lineId } ?: return fail(s, "장바구니에 없는 줄이다")
        val cart = s.cart - line
        return ok(s.copy(cart = cart, cartProblem = s.cartProblem.takeIf { it != lineId }), "(앱) 뺐다: ${line.name}. ${ToolResults.cart(cart)}")
    }

    private fun cartQuantity(a: Action.CartQuantity, s: ShopState): Outcome {
        if (a.count !in 1..9) return fail(s, "수량은 1에서 9 사이다")
        if (s.cart.none { it.lineId == a.lineId }) return fail(s, "장바구니에 없는 줄이다")
        val cart = s.cart.map { if (it.lineId == a.lineId) it.copy(qty = a.count) else it }
        return ok(s.copy(cart = cart), "(앱) 수량을 바꿨다. ${ToolResults.cart(cart)}")
    }

    private fun checkout(s: ShopState): Outcome {
        if (s.cart.isEmpty()) return fail(s, "장바구니가 비었다")
        return ok(s.copy(screen = Screen.Confirm, cartProblem = null), "주문 확인 화면을 열었다")
    }

    // 결제 안전은 말 해석이 아니라 상태로 지킨다: 주문 확인 화면(대본으로 금액을 읽어 준 뒤)에서만 확정된다.
    private suspend fun place(s: ShopState): Outcome {
        if (s.effectiveScreen != Screen.Confirm || s.cart.isEmpty()) return fail(s, "주문 확인 화면이 아니다")
        val items = s.cart.map { OrderItemDto(it.productId, it.qty, it.options) }
        return when (val r = api.placeBatch(BatchOrderInDto(userId, items))) {
            is ApiResult.Ok -> ok(
                s.copy(screen = Screen.Done, cart = emptyList(), orderIds = r.value.orders.map { it.orderId }, orderTotal = r.value.totalPrice, offline = false),
                "주문을 확정했다",
            )
            is ApiResult.Http -> {
                // 한 줄이라도 안 되면 서버는 아무것도 주문하지 않는다. 그 줄을 짚어 장바구니로 돌아간다
                val problem = r.order?.index?.let { s.cart.getOrNull(it) }
                fail(s.copy(screen = Screen.Cart, cartProblem = problem?.lineId), (problem?.name?.let { "$it: " } ?: "") + r.message, r.order?.choices.orEmpty())
            }
            is ApiResult.Network -> apiFail(s, r)
        }
    }

    private fun cancel(s: ShopState): Outcome {
        if (s.effectiveScreen != Screen.Confirm) return fail(s, "주문 확인 화면이 아니다")
        return ok(s.copy(screen = Screen.Cart), "주문을 멈추고 장바구니로 돌아갔다")
    }

    private suspend fun history(s: ShopState): Outcome = when (val r = api.orders(userId, 10)) {
        is ApiResult.Ok -> ok(s.copy(offline = false).remember(r.value.distinctBy { it.productId }.map { KnownProduct(it.productId, it.productName) }),
            ToolResults.history(r.value))
        else -> apiFail(s, r)
    }

    private suspend fun showHistory(s: ShopState): Outcome = when (val r = api.orders(userId, 10)) {
        is ApiResult.Ok -> {
            val past = r.value.map { PastOrder(it.orderedAt.take(10), it.productId, it.productName, it.quantity, it.options, it.totalPrice) }
            ok(
                s.copy(screen = Screen.History, pastOrders = past, offline = false)
                    .remember(r.value.distinctBy { it.productId }.map { KnownProduct(it.productId, it.productName) }),
                "주문 내역 화면을 열었다",
            )
        }
        else -> apiFail(s, r)
    }

    private fun ok(s: ShopState, result: String) = Outcome(s, ok = true, result = result)

    private fun fail(s: ShopState, message: String, choices: Map<String, List<String>> = emptyMap()) =
        Outcome(s, ok = false, result = "(앱) 실패: $message", choices = choices)

    private fun apiFail(s: ShopState, r: ApiResult<*>): Outcome = when (r) {
        is ApiResult.Network -> Outcome(s.copy(offline = true), ok = false, result = "(앱) 실패: 서버에 연결하지 못했다", offline = true)
        is ApiResult.Http -> fail(s, if (r.code == 404) "없는 상품이다" else "서버 오류 ${r.code}: ${r.message}", r.order?.choices.orEmpty())
        is ApiResult.Ok -> error("성공 결과는 여기로 오지 않는다")
    }
}
