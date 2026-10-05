package com.agentpjt.shop.agent

import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.OrderInDto
import com.agentpjt.shop.api.SearchQuery
import com.agentpjt.shop.api.ShopApi
import com.agentpjt.shop.shop.DEMO_USER_ID
import com.agentpjt.shop.shop.KnownProduct
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.toDetail
import com.agentpjt.shop.shop.toSummary

/**
 * 행동을 실행한다. 서버(ShopApi)를 부르고, 상태를 받아 새 상태와 결과를 낸다.
 * 여기서 하는 검사는 확실한 것뿐이다: 화면 상태, id·옵션 값 존재, 숫자 범위, 서버 응답. 사용자 말은 보지 않는다.
 * 터치 동작도 같은 실행기를 거친다(ShopViewModel) — 주문 경로가 하나라서 안전 검사도 한 곳에 있다.
 */
class ToolExecutor(private val api: ShopApi, private val userId: String = DEMO_USER_ID) {

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
        is Action.Show -> show(action, s)
        is Action.Open -> open(action.productId, s)
        is Action.Option -> option(action, s)
        is Action.Quantity -> quantity(action.count, s)
        is Action.Order -> order(action.quantity, s)
        Action.Place -> place(s)
        Action.Cancel -> cancel(s)
        Action.Home -> ok(ShopState(ai = s.ai, micDenied = s.micDenied), "처음 화면으로 갔다")
        // 말하기는 상태를 바꾸지 않는다. 화면이 문장을 그린다
        is Action.Ask, is Action.Answer -> ok(s, "")
    }

    private suspend fun search(a: Action.Search, s: ShopState): Outcome {
        val query = SearchQuery(
            q = a.query, minPrice = a.minPrice, maxPrice = a.maxPrice, priceTier = a.priceTier,
            sort = a.sort, gift = a.gift, audience = a.audience,
        )
        return when (val r = api.search(query)) {
            is ApiResult.Ok -> {
                val items = r.value.products.map { it.toSummary() }
                val next = s.copy(candidates = items, offline = false).remember(items.map { KnownProduct(it.id, it.name) })
                ok(next, ToolResults.search(a.query, r.value.total, items))
            }
            else -> apiFail(s, r)
        }
    }

    private fun show(a: Action.Show, s: ShopState): Outcome {
        val pool = (s.candidates + s.shown).associateBy { it.id }
        val products = a.ids.distinct().take(3).mapNotNull { pool[it] }
        if (products.isEmpty() || products.size != a.ids.distinct().take(3).size) return fail(s, "최근 검색 결과에 없는 상품이다")
        val next = s.copy(screen = Screen.Results, shown = products, label = a.label.ifBlank { "추천 상품" })
        return ok(next, "추천 화면에 ${products.size}개를 띄웠다")
    }

    private suspend fun info(id: String, s: ShopState): Outcome = when (val r = api.product(id)) {
        is ApiResult.Ok -> ok(s.copy(offline = false), ToolResults.detail(r.value.toDetail()))
        else -> apiFail(s, r)
    }

    private suspend fun open(id: String, s: ShopState): Outcome = when (val r = api.product(id)) {
        is ApiResult.Ok -> {
            val d = r.value.toDetail()
            ok(
                s.copy(screen = Screen.Detail, current = d, selected = emptyMap(), qty = 1, offline = false).remember(listOf(KnownProduct(d.id, d.name))),
                "${d.name} 상세 화면을 열었다",
            )
        }
        else -> apiFail(s, r)
    }

    private fun option(a: Action.Option, s: ShopState): Outcome {
        val d = s.current ?: return fail(s, "보고 있는 상품이 없다")
        val axis = d.options.firstOrNull { it.name == a.name } ?: return fail(s, "없는 옵션: ${a.name}")
        val v = axis.values.firstOrNull { it.value == a.value } ?: return fail(s, "${axis.name} 에 없는 값: ${a.value}", mapOf(axis.name to axis.available))
        if (v.soldOut) return fail(s, "${axis.name} ${v.value} 는 품절이다", mapOf(axis.name to axis.available))
        return ok(s.copy(selected = s.selected + (axis.name to v.value)), "${axis.name} ${v.value} 를 골랐다")
    }

    private fun quantity(count: Int, s: ShopState): Outcome {
        if (count !in 1..9) return fail(s, "수량은 1에서 9 사이다")
        if (s.current == null) return fail(s, "보고 있는 상품이 없다")
        return ok(s.copy(qty = count), "수량을 ${count}개로 바꿨다")
    }

    private fun order(quantity: Int?, s: ShopState): Outcome {
        val d = s.current ?: return fail(s, "보고 있는 상품이 없다")
        if (d.soldOut) return fail(s, "품절된 상품이라 주문할 수 없다")
        val qty = quantity ?: s.qty
        if (qty !in 1..9) return fail(s, "수량은 1에서 9 사이다")
        // 옵션이 빠졌으면 확인 화면으로 넘기지 않는다. 상세 화면에서 그 옵션을 고르게 한다
        val missing = d.missing(s.selected)
        if (missing.isNotEmpty()) {
            return fail(s.copy(screen = Screen.Detail, qty = qty), "옵션을 먼저 골라야 한다: ${missing.keys.joinToString()}", missing)
        }
        return ok(s.copy(screen = Screen.Confirm, qty = qty), "주문 확인 화면을 열었다")
    }

    // 주문 안전은 말 해석이 아니라 상태로 지킨다: 확인 화면(대본으로 금액을 읽어 준 뒤)에서만 확정된다.
    private suspend fun place(s: ShopState): Outcome {
        val d = s.current
        if (s.effectiveScreen != Screen.Confirm || d == null) return fail(s, "주문 확인 화면이 아니다")
        return when (val r = api.placeOrder(OrderInDto(userId, d.id, s.qty, s.selected))) {
            is ApiResult.Ok -> ok(
                s.copy(screen = Screen.Done, orderId = r.value.orderId, orderTotal = r.value.totalPrice, offline = false),
                "주문을 확정했다. 주문번호 ${r.value.orderId}",
            )
            is ApiResult.Http -> {
                // 422(옵션)·409(품절): 상세로 되돌려 다시 고르게 한다
                val choices = r.order?.choices.orEmpty()
                fail(s.copy(screen = Screen.Detail, selected = s.selected - choices.keys), r.message, choices)
            }
            is ApiResult.Network -> apiFail(s, r)
        }
    }

    private fun cancel(s: ShopState): Outcome {
        if (s.effectiveScreen != Screen.Confirm || s.current == null) return fail(s, "주문 확인 화면이 아니다")
        return ok(s.copy(screen = Screen.Detail), "주문을 멈추고 상세 화면으로 돌아갔다")
    }

    private suspend fun history(s: ShopState): Outcome = when (val r = api.orders(userId, 5)) {
        is ApiResult.Ok -> {
            val items = r.value.distinctBy { it.productId }.map { KnownProduct(it.productId, it.productName) }
            ok(s.copy(offline = false).remember(items), ToolResults.history(r.value))
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
