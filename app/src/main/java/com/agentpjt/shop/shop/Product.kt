package com.agentpjt.shop.shop

import com.agentpjt.shop.api.OptionAxisDto
import com.agentpjt.shop.api.ProductCompactDto
import com.agentpjt.shop.api.ProductFullDto

/** 추천 카드와 모델 판단용 요약(서버 compact). */
data class ProductSummary(
    val id: String,
    val name: String,
    val brand: String,
    val sub: String,
    val price: Int,
    val priceSpoken: String,
    val tier: String,
    val rating: Double,
    val reviews: Int,
    val badges: List<String>,
    val arriveSpoken: String,
    val stock: String,
    val gift: Boolean,
    val image: String,
    /** 옵션 이름 → 지금 고를 수 있는 값 */
    val options: Map<String, List<String>>,
) {
    val isRocket: Boolean get() = "로켓배송" in badges
    val isFreeShipping: Boolean get() = "무료배송" in badges
}

data class OptionValue(val value: String, val priceAdd: Int, val soldOut: Boolean)

data class OptionAxis(val name: String, val values: List<OptionValue>) {
    val available: List<String> get() = values.filterNot { it.soldOut }.map { it.value }
}

/** 상세·확인 화면용 전체 정보(서버 full). */
data class ProductDetail(
    val id: String,
    val name: String,
    val brand: String,
    val sub: String,
    val price: Int,
    val shippingFee: Int,
    val tier: String,
    val rating: Double,
    val reviews: Int,
    val badges: List<String>,
    val arriveLabel: String,
    val arriveSpoken: String,
    val spec: String,
    val origin: String,
    val description: String,
    val reviewSummary: String?,
    val soldOut: Boolean,
    val image: String,
    val options: List<OptionAxis>,
) {
    /** (가격 + 고른 옵션의 추가 금액) × 수량 + 배송비. 서버 POST /orders 와 같은 계산 */
    fun total(qty: Int, selected: Map<String, String>): Int {
        val add = options.sumOf { axis -> axis.values.firstOrNull { it.value == selected[axis.name] }?.priceAdd ?: 0 }
        return (price + add) * qty + shippingFee
    }

    /** 아직 고르지 않은 옵션 → 고를 수 있는 값 */
    fun missing(selected: Map<String, String>): Map<String, List<String>> =
        options.filter { it.name !in selected }.associate { it.name to it.available }
}

fun ProductCompactDto.toSummary() = ProductSummary(
    id = id, name = name, brand = brand, sub = sub, price = price, priceSpoken = priceSpoken, tier = tier,
    rating = rating, reviews = reviews, badges = badges, arriveSpoken = arrive, stock = stock, gift = gift,
    image = image, options = options.associate { it.name to it.values },
)

fun ProductFullDto.toDetail() = ProductDetail(
    id = productId, name = productName, brand = brand, sub = category.sub.name,
    price = pricing.price, shippingFee = delivery.shippingFee, tier = pricing.priceTier.label,
    rating = stats.rating, reviews = stats.reviewCount, badges = stats.badges,
    arriveLabel = delivery.arriveLabel, arriveSpoken = delivery.arriveSpoken,
    spec = spec.text, origin = origin, description = content.description, reviewSummary = content.reviewSummary,
    soldOut = stock.status == "sold_out", image = productImage, options = options.map { it.toAxis() },
)

private fun OptionAxisDto.toAxis() = OptionAxis(name, values.map { OptionValue(it.value, it.priceAdd, it.stock == "sold_out") })
