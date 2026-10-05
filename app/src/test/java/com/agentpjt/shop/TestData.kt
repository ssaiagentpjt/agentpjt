package com.agentpjt.shop

import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.CategoryRefDto
import com.agentpjt.shop.api.ContentDto
import com.agentpjt.shop.api.DeliveryDto
import com.agentpjt.shop.api.NamedDto
import com.agentpjt.shop.api.OptionAxisDto
import com.agentpjt.shop.api.OptionBriefDto
import com.agentpjt.shop.api.OptionValueDto
import com.agentpjt.shop.api.OrderDto
import com.agentpjt.shop.api.OrderErrorDetailDto
import com.agentpjt.shop.api.OrderInDto
import com.agentpjt.shop.api.PriceTierDto
import com.agentpjt.shop.api.PricingDto
import com.agentpjt.shop.api.ProductCompactDto
import com.agentpjt.shop.api.ProductFullDto
import com.agentpjt.shop.api.SearchDto
import com.agentpjt.shop.api.SearchQuery
import com.agentpjt.shop.api.ShopApi
import com.agentpjt.shop.api.SpecDto
import com.agentpjt.shop.api.StatsDto
import com.agentpjt.shop.api.StockDto
import com.agentpjt.shop.api.UnitPriceDto
import com.agentpjt.shop.shop.readWon

/** 테스트용 상품. 무릎 보호대(p07002)는 사이즈 옵션이 있고 XL 은 +2,000원, 3XL 은 품절이다. */
object TestData {

    fun compact(id: String, name: String, price: Int, options: Map<String, List<String>> = emptyMap()) = ProductCompactDto(
        id = id, name = name, brand = "든든걸음", sub = "보호대", price = price, priceSpoken = readWon(price), tier = "보통 가격",
        discount = 0, rating = 4.5, reviews = 100, rankInMid = 1, badges = listOf("로켓배송"), arrive = "내일", stock = "in_stock",
        gift = false, image = "https://x/static/icons/silver.svg", options = options.map { OptionBriefDto(it.key, it.value) },
    )

    fun full(id: String, name: String, price: Int, fee: Int = 0, options: List<OptionAxisDto> = emptyList(), soldOut: Boolean = false) =
        ProductFullDto(
            productId = id, productName = name, brand = "든든걸음",
            category = CategoryRefDto(NamedDto("silver", "실버·보조용품"), NamedDto("protector", "보호대"), NamedDto("knee-protector", "무릎 보호대")),
            origin = "국산", audience = "senior", isGift = false,
            pricing = PricingDto(price, null, 0, UnitPriceDto(price, "1개"), PriceTierDto("mid", "보통 가격", 50)),
            spec = SpecDto("2개", 2.0, "개"),
            delivery = DeliveryDto(isRocket = fee == 0, isFreeShipping = false, shippingFee = fee, deliveryDays = if (fee == 0) 1 else 3,
                arriveLabel = "내일 10월 6일(화)", arriveSpoken = "내일"),
            stats = StatsDto(100, 4.5, 100, 10, 1, listOf("로켓배송")),
            content = ContentDto("무릎을 감싸 주는 보호대예요.", "따뜻하다는 평이 많아요."),
            stock = StockDto(if (soldOut) "sold_out" else "in_stock", if (soldOut) 0 else 100),
            options = options, productImage = "https://x/static/icons/silver.svg",
        )

    val sizeAxis = OptionAxisDto("사이즈", listOf(
        OptionValueDto("M", 0, "in_stock"), OptionValueDto("L", 0, "in_stock"),
        OptionValueDto("XL", 2000, "low"), OptionValueDto("3XL", 0, "sold_out"),
    ))

    val knee = full("p07002", "무릎 보호대 2개입", 16900, options = listOf(sizeAxis))
    val cane = full("p07001", "접이식 알루미늄 지팡이", 19900, fee = 3000)
}

/** 서버 대신 쓰는 가짜. 응답을 바꿔 끼우고, 받은 요청을 기록한다. */
class FakeShopApi : ShopApi {
    var searchResult: ApiResult<SearchDto> = ApiResult.Ok(SearchDto("", 0, emptyList()))
    val products = mutableMapOf<String, ProductFullDto>()
    var orderResult: ApiResult<OrderDto>? = null
    var history: ApiResult<List<OrderDto>> = ApiResult.Ok(emptyList())
    var offline = false

    val searches = mutableListOf<SearchQuery>()
    val orders = mutableListOf<OrderInDto>()

    private val net = ApiResult.Network(java.io.IOException("offline"))

    override suspend fun search(query: SearchQuery): ApiResult<SearchDto> {
        searches += query
        return if (offline) net else searchResult
    }

    override suspend fun product(id: String): ApiResult<ProductFullDto> = when {
        offline -> net
        else -> products[id]?.let { ApiResult.Ok(it) } ?: ApiResult.Http(404, "없는 상품: $id")
    }

    override suspend fun placeOrder(order: OrderInDto): ApiResult<OrderDto> {
        orders += order
        if (offline) return net
        return orderResult ?: ApiResult.Ok(OrderDto("M-20261005-1234", order.userId, order.productId, "상품", order.quantity, order.options, 1, "2026-10-05T10:00:00+09:00"))
    }

    override suspend fun orders(userId: String, limit: Int): ApiResult<List<OrderDto>> = if (offline) net else history

    companion object {
        fun optionError(code: Int, choices: Map<String, List<String>>) =
            ApiResult.Http(code, "옵션을 골라야 한다", OrderErrorDetailDto(message = "옵션을 골라야 한다", choices = choices))
    }
}
