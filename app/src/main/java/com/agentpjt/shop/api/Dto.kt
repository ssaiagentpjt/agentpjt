package com.agentpjt.shop.api

import kotlinx.serialization.Serializable

// 서버(server/app/models.py) 응답 모양. 서버에 필드가 늘어도 깨지지 않게 Json 은 ignoreUnknownKeys 로 읽는다(ShopApi.kt).

@Serializable
data class OptionBriefDto(val name: String, val values: List<String>)

/** 검색 기본 응답(view=compact). 모델 판단과 추천 카드에 쓴다. */
@Serializable
data class ProductCompactDto(
    val id: String,
    val name: String,
    val brand: String,
    val sub: String,
    val price: Int,
    val priceSpoken: String,
    val tier: String,
    val discount: Int,
    val rating: Double,
    val reviews: Int,
    val rankInMid: Int,
    val badges: List<String>,
    val arrive: String,
    val stock: String,
    val gift: Boolean,
    val image: String = "",
    val options: List<OptionBriefDto> = emptyList(),
)

@Serializable
data class SearchDto(val query: String, val total: Int, val products: List<ProductCompactDto>)

@Serializable
data class NamedDto(val id: String, val name: String)

@Serializable
data class CategoryRefDto(val main: NamedDto, val mid: NamedDto, val sub: NamedDto)

@Serializable
data class PriceTierDto(val level: String, val label: String, val percentile: Int)

@Serializable
data class UnitPriceDto(val value: Int, val per: String)

@Serializable
data class PricingDto(
    val price: Int,
    val originalPrice: Int? = null,
    val discountRate: Int,
    val unitPrice: UnitPriceDto,
    val priceTier: PriceTierDto,
)

@Serializable
data class SpecDto(val text: String, val quantity: Double, val unit: String)

@Serializable
data class DeliveryDto(
    val isRocket: Boolean,
    val isFreeShipping: Boolean,
    val shippingFee: Int,
    val deliveryDays: Int,
    val arriveLabel: String,
    val arriveSpoken: String,
)

@Serializable
data class StatsDto(
    val salesCount30d: Int,
    val rating: Double,
    val reviewCount: Int,
    val repurchaseRate: Int,
    val rankInMid: Int,
    val badges: List<String>,
)

@Serializable
data class ContentDto(val description: String, val reviewSummary: String? = null, val tags: List<String> = emptyList())

@Serializable
data class StockDto(val status: String, val quantity: Int)

@Serializable
data class OptionValueDto(val value: String, val priceAdd: Int, val stock: String)

@Serializable
data class OptionAxisDto(val name: String, val values: List<OptionValueDto>)

/** 상품 전체 정보(view=full). 상세·확인 화면과 getProductInfo 에 쓴다. */
@Serializable
data class ProductFullDto(
    val productId: String,
    val productName: String,
    val brand: String,
    val category: CategoryRefDto,
    val origin: String,
    val audience: String,
    val isGift: Boolean,
    val pricing: PricingDto,
    val spec: SpecDto,
    val delivery: DeliveryDto,
    val stats: StatsDto,
    val content: ContentDto,
    val stock: StockDto,
    val options: List<OptionAxisDto> = emptyList(),
    val productImage: String,
)

/** 장바구니 결제: 모든 줄을 서버가 먼저 검사하고, 하나라도 안 되면 아무것도 주문하지 않는다(POST /orders/batch). */
@Serializable
data class BatchOrderInDto(val userId: String, val items: List<OrderItemDto>)

@Serializable
data class OrderItemDto(val productId: String, val quantity: Int, val options: Map<String, String>)

@Serializable
data class BatchOrderDto(val orders: List<OrderDto>, val totalPrice: Int)

@Serializable
data class OrderDto(
    val orderId: String,
    val userId: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val options: Map<String, String> = emptyMap(),
    val totalPrice: Int,
    val orderedAt: String,
)

/** 주문 실패 본문의 detail(422 옵션·409 품절·404). choices 로 모델이 되묻는다. */
@Serializable
data class OrderErrorDetailDto(
    val message: String = "",
    val missing: List<String> = emptyList(),
    val invalid: Map<String, String> = emptyMap(),
    val unknownOptions: List<String> = emptyList(),
    val soldOut: List<String> = emptyList(),
    val choices: Map<String, List<String>> = emptyMap(),
    /** 여러 상품 주문에서 문제가 된 줄(0부터) */
    val index: Int? = null,
    val productId: String? = null,
)
