package com.agentpjt.shop.agent

import com.agentpjt.shop.api.OrderDto
import com.agentpjt.shop.shop.CartLine
import com.agentpjt.shop.shop.ProductDetail
import com.agentpjt.shop.shop.ProductSummary
import com.agentpjt.shop.shop.readWon
import com.agentpjt.shop.shop.total

/**
 * 관찰 행동(search·info·history·장바구니 편집)의 결과를 모델에게 돌려줄 평문. "(앱)"으로 시작해 사용자 말과 구분한다.
 * 온디바이스 모델은 입력이 길수록 느리고 판단이 흐려져서 고르는 데 필요한 사실만 담는다.
 */
object ToolResults {

    fun search(query: String, total: Int, items: List<ProductSummary>): String =
        if (items.isEmpty()) {
            "(앱) '$query' 검색 결과가 없다."
        } else {
            "(앱) '$query' 검색 결과 ${total}개 중 ${items.size}개:\n" + items.joinToString("\n") { it.line() }
        }

    private fun ProductSummary.line() = buildString {
        append("$id $name · $priceSpoken · $tier · 별점 $rating")
        if (badges.isNotEmpty()) append(" · ${badges.take(2).joinToString("/")}")
        if (options.isNotEmpty()) append(" · 옵션 " + options.entries.joinToString(" ") { "${it.key}(${it.value.joinToString("/")})" })
        if (stock == "low") append(" · 품절 임박")
    }

    fun detail(d: ProductDetail): String = buildString {
        append("(앱) ${d.id} ${d.name} 정보: ${readWon(d.price)} · 규격 ${d.spec} · ")
        append(if (d.shippingFee > 0) "배송비 ${readWon(d.shippingFee)}" else "배송비 없음")
        append(" · ${d.arriveSpoken} 도착 · ${d.description}")
        d.reviewSummary?.let { append(" · 후기: $it") }
        if (d.options.isNotEmpty()) append(" · " + options(d))
        if (d.soldOut) append(" · 품절")
    }

    /** 옵션 축과 값. 추가 금액·품절을 붙인다. 상세 턴 메시지에서도 쓴다 */
    fun options(d: ProductDetail): String = d.options.joinToString(" · ") { axis ->
        "${axis.name}(" + axis.values.joinToString("/") { v ->
            v.value + (if (v.priceAdd > 0) " +${readWon(v.priceAdd)}" else "") + (if (v.soldOut) " 품절" else "")
        } + ")"
    }

    fun history(orders: List<OrderDto>): String =
        if (orders.isEmpty()) {
            "(앱) 지난 구매가 없다."
        } else {
            "(앱) 지난 구매(최근 순):\n" + orders.joinToString("\n") { o ->
                "${o.orderedAt.take(10)} ${o.productId} ${o.productName} ${o.quantity}개" +
                    if (o.options.isEmpty()) "" else " (" + o.options.entries.joinToString { "${it.key} ${it.value}" } + ")"
            }
        }

    /** 장바구니 줄 한 줄. 턴 메시지와 편집 결과에서 함께 쓴다 */
    fun line(l: CartLine): String =
        "${l.lineId} ${l.name}" + (if (l.options.isEmpty()) "" else " (" + l.options.values.joinToString(" ") + ")") + " ${l.qty}개 · ${readWon(l.total)}"

    fun cart(cart: List<CartLine>): String =
        if (cart.isEmpty()) "장바구니가 비었다." else "장바구니 ${cart.size}가지: " + cart.joinToString(" / ") { line(it) } + " / 합계 ${readWon(cart.total())}"
}
